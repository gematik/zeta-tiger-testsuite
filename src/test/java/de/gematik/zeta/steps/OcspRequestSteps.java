/*
 * #%L
 * ZETA Testsuite
 * %%
 * (C) achelos GmbH, 2025, licensed for gematik GmbH
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * *******
 *
 * For additional notes and disclaimer from gematik and in case of changes by gematik find details in the "Readme" file.
 * #L%
 */

package de.gematik.zeta.steps;

import de.gematik.rbellogger.data.RbelElement;
import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.test.tiger.lib.TigerHttpClient;
import de.gematik.test.tiger.lib.rbel.RbelMessageRetriever;
import de.gematik.zeta.model.CertificateMaterial;
import de.gematik.zeta.model.tls.TlsServerCertificates;
import de.gematik.zeta.services.RecordedMessages;
import de.gematik.zeta.services.TlsTestToolServiceFactory;
import de.gematik.zeta.steps.tls.TlsTestToolCertificateFixtures;
import io.cucumber.java.After;
import io.cucumber.java.Before;
import io.cucumber.java.de.Angenommen;
import io.cucumber.java.de.Dann;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509CRL;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.assertj.core.api.Assertions;
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder;
import org.bouncycastle.cert.ocsp.BasicOCSPResp;
import org.bouncycastle.cert.ocsp.CertificateID;
import org.bouncycastle.cert.ocsp.OCSPException;
import org.bouncycastle.cert.ocsp.OCSPReq;
import org.bouncycastle.cert.ocsp.OCSPReqBuilder;
import org.bouncycastle.cert.ocsp.OCSPResp;
import org.bouncycastle.cert.ocsp.Req;
import org.bouncycastle.cert.ocsp.RevokedStatus;
import org.bouncycastle.cert.ocsp.SingleResp;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentVerifierProviderBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Cucumber steps for validating OCSP request protocol evidence captured by RBEL.
 */
public class OcspRequestSteps {

  private static final Path CERTIFICATE_DIRECTORY =
      Path.of("src", "test", "resources", "tls-test-tool", "certificates");
  private static final Path DEFAULT_CA_CERTIFICATE =
      Path.of("ecdsa", "zeta-tls-test-tool-server_CA.cer");
  private static final Duration MAX_OCSP_NEXT_UPDATE_WAIT = Duration.ofMinutes(2);

  private Instant lastVerifiedOcspNextUpdate;
  private boolean restoreSmcbStatus;
  private JsonNode originalMockConfiguration;
  private JsonNode ownedMockConfiguration;
  private JsonNode previousOwnedMockConfiguration;
  private final Runnable discardSmcbCache;

  /** Creates steps backed by the configured AS deployment. */
  public OcspRequestSteps() {
    this(() -> new DeploymentModificationSteps().restartDeployment(
        TigerGlobalConfiguration.resolvePlaceholders("${zetaDeploymentConfig.authserver.podName}")));
  }

  /**
   * Creates steps with an explicit cache invalidation operation.
   *
   * @param discardSmcbCache operation discarding cached SMC-B OCSP results
   */
  OcspRequestSteps(Runnable discardSmcbCache) {
    this.discardSmcbCache = discardSmcbCache;
  }

  /** Enables restoration for the SMC-B OCSP status scenario. */
  @Before("@smcb_ocsp_status")
  public void enableSmcbStatusRestoration() {
    restoreSmcbStatus = true;
  }

  /**
   * Restores owned mock configuration and discards cached SMC-B status after partial failures.
   *
   * @throws Exception if restoration or deployment readiness cannot be confirmed
   */
  @After("@smcb_ocsp_status")
  public void restoreSmcbStatusConfiguration() throws Exception {
    if (originalMockConfiguration == null) {
      return;
    }
    try {
      var uri = mockAdminUri("mode");
      var current = TigerHttpClient.givenDefaultSpec().get(mockAdminUri("config"));
      Assertions.assertThat(current.statusCode()).isEqualTo(200);
      var json = new ObjectMapper();
      var actual = json.readTree(current.asByteArray());
      Assertions.assertThat(configurationMatches(actual, ownedMockConfiguration)
          || configurationMatches(actual, previousOwnedMockConfiguration))
          .as("OCSP mock configuration still belongs to this scenario").isTrue();
      var restored = TigerHttpClient.givenDefaultSpec().contentType("application/json")
          .body(originalMockConfiguration.toString()).post(uri);
      Assertions.assertThat(restored.statusCode()).isEqualTo(200);
      var observed = json.readTree(restored.asByteArray());
      for (var field : originalMockConfiguration.properties()) {
        Assertions.assertThat(observed.get(field.getKey())).isEqualTo(field.getValue());
      }
    } finally {
      discardSmcbCache.run();
    }
  }

  /**
   * Compares the mutable fields of an owned configuration with current state.
   *
   * @param actual observed configuration
   * @param expected last attempted or previously confirmed configuration
   * @return whether all owned fields still match
   */
  private static boolean configurationMatches(JsonNode actual, JsonNode expected) {
    return expected != null && expected.properties().stream()
        .allMatch(field -> field.getValue().equals(actual.get(field.getKey())));
  }

  /**
   * Resolves a certificate-validation mock administration endpoint beneath its configured base.
   *
   * @param endpoint administration endpoint name
   * @return configured administration URI
   */
  private URI mockAdminUri(String endpoint) {
    var configured = TigerGlobalConfiguration.readStringOptional("zeta_cert_validation_mock_url")
        .map(TigerGlobalConfiguration::resolvePlaceholders)
        .orElseThrow(() -> new AssertionError("Missing configuration zeta_cert_validation_mock_url."));
    return URI.create((configured.matches("^https?://.*") ? configured : "http://" + configured)
        .replaceAll("/+$", "") + "/admin/" + endpoint);
  }

  /**
   * Configures the certificate-validation mock to return the requested deterministic OCSP status.
   *
   * @param mode mock mode, either {@code always_good} or {@code always_revoked}
   */
  @Angenommen("der Zertifikatsvalidierungs-Mock verwendet den Modus {string}")
  @Given("the certificate validation mock uses mode {string}")
  public void certificateValidationMockUsesMode(String mode) {
    configureCertificateValidationMock(
        mode, 0, 43_200, "normal", true, true, java.util.Set.of());
  }

  /**
   * Configures the mock to revoke only the supplied certificate serial number.
   *
   * @param certificate Base64 DER or PEM encoded certificate
   */
  @Angenommen("der Zertifikatsvalidierungs-Mock meldet nur das Zertifikat {tigerResolvedString} als widerrufen")
  public void certificateValidationMockRevokesOnlyCertificate(String certificate) {
    var leaf = CertificateMaterial.parseCertificate(certificate, "revoked certificate");
    configureCertificateValidationMock("always_good", -5, 43_200, "normal", true, true,
        java.util.Set.of(leaf.getSerialNumber().toString()));
  }

  /**
   * Configures the mock to report only one certificate in a fixture chain as revoked.
   *
   * @param position zero-based certificate position in the PEM chain
   * @param certificate certificate-chain fixture
   */
  @Angenommen(
      "der Zertifikatsvalidierungs-Mock meldet nur das Zertifikat an Position {int} der Zertifikatskette {tlsServerCertificate} als widerrufen")
  @Given(
      "the certificate validation mock reports only certificate at position {int} of chain {tlsServerCertificate} as revoked")
  public void certificateValidationMockRevokesOnlyChainPosition(
      int position, TlsServerCertificates certificate) {
    try {
      var chain = readCertificateChain(certificate);
      Assertions.assertThat(position)
          .as("certificate position in fixture chain")
          .isBetween(0, chain.size() - 2);
      configureCertificateValidationMock(
          "always_good",
          0,
          43_200,
          "normal",
          true,
          true,
          java.util.Set.of(chain.get(position).getSerialNumber().toString()));
    } catch (IOException | GeneralSecurityException e) {
      throw new AssertionError("Failed to read certificate chain fixture.", e);
    }
  }

  /**
   * Configures deterministic OCSP response validity offsets.
   *
   * @param mode deterministic mock mode
   * @param thisUpdateOffsetSeconds offset of {@code thisUpdate} from now
   * @param nextUpdateOffsetSeconds offset of {@code nextUpdate} from now
   */
  @Angenommen(
      "der Zertifikatsvalidierungs-Mock verwendet den Modus {string} mit ThisUpdate-Offset {int} und NextUpdate-Offset {int} Sekunden")
  @Given(
      "the certificate validation mock uses mode {string} with thisUpdate offset {int} and nextUpdate offset {int} seconds")
  public void certificateValidationMockUsesTiming(
      String mode, int thisUpdateOffsetSeconds, int nextUpdateOffsetSeconds) {
    configureCertificateValidationMock(
        mode,
        thisUpdateOffsetSeconds,
        nextUpdateOffsetSeconds,
        "normal",
        true,
        true,
        java.util.Set.of());
  }

  /**
   * Configures a deterministic negative-test OCSP response variant.
   *
   * @param mode deterministic mock mode
   * @param responseVariant response variant name
   */
  @Angenommen(
      "der Zertifikatsvalidierungs-Mock verwendet den Modus {string} mit OCSP-Antwortvariante {string}")
  @Given(
      "the certificate validation mock uses mode {string} with OCSP response variant {string}")
  public void certificateValidationMockUsesResponseVariant(String mode, String responseVariant) {
    configureCertificateValidationMock(
        mode, 0, 43_200, responseVariant, true, true, java.util.Set.of());
  }

  /** Configures both revocation endpoints to be unavailable. */
  @Angenommen("der Zertifikatsvalidierungs-Mock stellt weder OCSP noch CRL bereit")
  @Given("the certificate validation mock provides neither OCSP nor CRL")
  public void certificateValidationMockProvidesNeitherOcspNorCrl() {
    configureCertificateValidationMock(
        "always_good", 0, 43_200, "normal", false, false, java.util.Set.of());
  }

  /**
   * Obtains a deterministic OCSP response for the selected certificate from the mock and uploads
   * it to the TLS test tool as a one-shot stapled response.
   *
   * @param certificate TLS server certificate fixture
   */
  @Angenommen(
      "der TLS-Test-Tool-Server verwendet eine OCSP-Stapling-Antwort für das Zertifikat {tlsServerCertificate}")
  @Given(
      "the TLS test tool server uses a stapled OCSP response for certificate {tlsServerCertificate}")
  public void tlsTestToolServerUsesStapledOcspResponse(TlsServerCertificates certificate) {
    try {
      var request = new OCSPReqBuilder()
          .addRequest(buildExpectedCertificateId(certificate))
          .build()
          .getEncoded();
      var configuredUrl = TigerGlobalConfiguration.readStringOptional("zeta_cert_validation_mock_url")
          .map(TigerGlobalConfiguration::resolvePlaceholders)
          .orElseThrow(() -> new AssertionError("Missing configuration zeta_cert_validation_mock_url."));
      var baseUrl = (configuredUrl.matches("^https?://.*") ? configuredUrl : "http://" + configuredUrl)
          .replaceAll("/+$", "");
      var response = TigerHttpClient.givenDefaultSpec()
          .contentType("application/ocsp-request")
          .body(request)
          .post(URI.create(baseUrl + "/ocsp/tls"));
      Assertions.assertThat(response.statusCode())
          .as("certificate-validation mock OCSP response status")
          .isEqualTo(200);
      var encodedResponse = response.asByteArray();
      Assertions.assertThat(encodedResponse)
          .as("certificate-validation mock OCSP response body")
          .isNotEmpty();
      TlsTestToolServiceFactory.getSession().api().updateOcspResponse(encodedResponse);
    } catch (IOException | OCSPException e) {
      throw new AssertionError("Failed to prepare the stapled OCSP response.", e);
    }
  }

  /**
   * Uploads an issuer-specific X.509 CRL fixture to the mock runtime API.
   *
   * @param issuer issuer identifier used in the CRL URL
   * @param relativePath CRL fixture path relative to the certificate fixture directory
   */
  @Angenommen(
      "der Zertifikatsvalidierungs-Mock stellt die CRL {string} aus der Datei {string} bereit")
  @Given(
      "the certificate validation mock provides CRL {string} from file {string}")
  public void certificateValidationMockProvidesCrl(String issuer, String relativePath) {
    var fixture = CERTIFICATE_DIRECTORY.resolve(relativePath).normalize();
    Assertions.assertThat(fixture)
        .as("CRL fixture path")
        .startsWith(CERTIFICATE_DIRECTORY.normalize());
    try (var inputStream = Files.newInputStream(fixture)) {
      var crl = (X509CRL) CertificateFactory.getInstance("X.509").generateCRL(inputStream);
      var configuredUrl = TigerGlobalConfiguration.readStringOptional("zeta_cert_validation_mock_url")
          .map(TigerGlobalConfiguration::resolvePlaceholders)
          .orElseThrow(() -> new AssertionError("Missing configuration zeta_cert_validation_mock_url."));
      var baseUrl = (configuredUrl.matches("^https?://.*") ? configuredUrl : "http://" + configuredUrl)
          .replaceAll("/+$", "");
      var response = TigerHttpClient.givenDefaultSpec()
          .contentType("application/pkix-crl")
          .body(crl.getEncoded())
          .put(URI.create(baseUrl + "/admin/crl/" + issuer));
      Assertions.assertThat(response.statusCode())
          .as("certificate-validation mock CRL upload status")
          .isEqualTo(204);
    } catch (IOException | GeneralSecurityException e) {
      throw new AssertionError("Failed to upload CRL fixture " + relativePath + ".", e);
    }
  }

  /**
   * Sends and verifies a complete runtime configuration update to the certificate-validation mock.
   *
   * @param mode deterministic mock mode
   * @param thisUpdateOffsetSeconds offset of {@code thisUpdate} from now
   * @param nextUpdateOffsetSeconds offset of {@code nextUpdate} from now
   * @param responseVariant response variant name
   * @param ocspAvailable whether OCSP endpoints are available
   * @param crlAvailable whether CRL endpoints are available
   * @param revokedSerialNumbers decimal certificate serials reported as revoked
   */
  private void configureCertificateValidationMock(
      String mode,
      int thisUpdateOffsetSeconds,
      int nextUpdateOffsetSeconds,
      String responseVariant,
      boolean ocspAvailable,
      boolean crlAvailable,
      java.util.Set<String> revokedSerialNumbers) {
    Assertions.assertThat(mode)
        .as("supported certificate-validation mock mode")
        .isIn("always_good", "always_revoked", "always_unknown");
    Assertions.assertThat(responseVariant)
        .as("supported OCSP response variant")
        .isIn("normal", "invalid_signature", "wrong_signer", "mismatched_certificate");

    var serializedRevokedSerials = revokedSerialNumbers.stream()
        .map(serial -> "\"" + serial + "\"")
        .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    var requestBody = String.format(
        "{\"mode\":\"%s\",\"thisUpdateOffsetSeconds\":%d,\"nextUpdateOffsetSeconds\":%d,"
            + "\"includeNextUpdate\":true,\"responseVariant\":\"%s\","
            + "\"ocspAvailable\":%s,\"crlAvailable\":%s,\"revokedSerialNumbers\":%s}",
        mode,
        thisUpdateOffsetSeconds,
        nextUpdateOffsetSeconds,
        responseVariant,
        ocspAvailable,
        crlAvailable,
        serializedRevokedSerials);
    if (restoreSmcbStatus) {
      try {
        var json = new ObjectMapper();
        if (originalMockConfiguration == null) {
          var previous = TigerHttpClient.givenDefaultSpec().get(mockAdminUri("config"));
          Assertions.assertThat(previous.statusCode()).isEqualTo(200);
          var original = (ObjectNode) json.readTree(previous.asByteArray());
          original.remove("seed");
          originalMockConfiguration = original;
          ownedMockConfiguration = original;
        }
        previousOwnedMockConfiguration = ownedMockConfiguration;
        ownedMockConfiguration = json.readTree(requestBody);
      } catch (JacksonException e) {
        throw new AssertionError("Cannot retain OCSP mock configuration for restoration", e);
      }
    }
    var response = TigerHttpClient.givenDefaultSpec()
        .contentType("application/json")
        .body(requestBody)
        .post(mockAdminUri("mode"));

    Assertions.assertThat(response.statusCode())
        .as("certificate-validation mock mode response status")
        .isEqualTo(200);
    Assertions.assertThat(response.jsonPath().getString("mode"))
        .as("configured certificate-validation mock mode")
        .isEqualTo(mode);
    Assertions.assertThat(response.jsonPath().getInt("thisUpdateOffsetSeconds"))
        .isEqualTo(thisUpdateOffsetSeconds);
    Assertions.assertThat(response.jsonPath().getInt("nextUpdateOffsetSeconds"))
        .isEqualTo(nextUpdateOffsetSeconds);
    Assertions.assertThat(response.jsonPath().getBoolean("includeNextUpdate"))
        .isTrue();
    Assertions.assertThat(response.jsonPath().getString("responseVariant"))
        .isEqualTo(responseVariant);
    Assertions.assertThat(response.jsonPath().getBoolean("ocspAvailable"))
        .isEqualTo(ocspAvailable);
    Assertions.assertThat(response.jsonPath().getBoolean("crlAvailable"))
        .isEqualTo(crlAvailable);
    Assertions.assertThat(response.jsonPath().getList("revokedSerialNumbers", String.class))
        .containsExactlyInAnyOrderElementsOf(revokedSerialNumbers);
  }

  /**
   * Verifies that the currently selected RBEL request contains an OCSP CertID
   * for the given TLS test tool certificate fixture.
   *
   * @param certificate expected TLS test tool certificate fixture
   */
  @Dann("die aktuelle OCSP-Anfrage fragt das Zertifikat {tlsServerCertificate} ab")
  @Then("the current OCSP request queries the certificate {tlsServerCertificate}")
  public void currentOcspRequestQueriesCertificate(TlsServerCertificates certificate) {
    assertOcspRequestContainsCertificateId(currentOcspRequestBytes(), certificate);
  }

  /**
   * Verifies an OCSP request for one certificate in a presented chain.
   *
   * @param certificate chain fixture
   * @param position zero-based certificate position in the PEM chain
   */
  @Dann(
      "die aktuelle OCSP-Anfrage fragt das Zertifikat an Position {int} der Zertifikatskette {tlsServerCertificate} ab")
  @Then(
      "the current OCSP request queries certificate at position {int} of chain {tlsServerCertificate}")
  public void currentOcspRequestQueriesCertificateAtChainPosition(
      int position, TlsServerCertificates certificate) {
    try {
      var chain = readCertificateChain(certificate);
      Assertions.assertThat(position)
          .as("certificate position in fixture chain")
          .isBetween(0, chain.size() - 2);
      assertOcspRequestContainsCertificateId(
          currentOcspRequestBytes(),
          chain.get(position),
          chain.get(position + 1),
          certificate.getCertificateId() + " at chain position " + position);
    } catch (IOException | GeneralSecurityException e) {
      throw new AssertionError("Failed to read certificate chain fixture.", e);
    }
  }

  /**
   * Verifies that the currently selected RBEL request contains an OCSP CertID
   * for the given certificate and issuer certificate.
   *
   * @param certificate Base64 DER or PEM encoded certificate whose revocation status is requested
   * @param issuerCertificate Base64 DER or PEM encoded issuer certificate
   */
  @Dann("die aktuelle OCSP-Anfrage fragt das Zertifikat {tigerResolvedString} mit Ausstellerzertifikat {tigerResolvedString} ab")
  @Then("the current OCSP request queries the certificate {tigerResolvedString} with issuer certificate {tigerResolvedString}")
  public void currentOcspRequestQueriesCertificateWithIssuer(String certificate, String issuerCertificate) {
    assertOcspRequestContainsCertificateId(
        currentOcspRequestBytes(),
        CertificateMaterial.parseCertificate(certificate, "certificate"),
        CertificateMaterial.parseCertificate(issuerCertificate, "issuer certificate"),
        "provided certificate");
  }

  /**
   * Validates the signed status and validity interval of the selected OCSP response.
   *
   * @param certificate certificate whose status is expected
   * @param issuerCertificate certificate issuer authorizing the responder
   * @param status expected status, {@code good} or {@code revoked}
   * @throws Exception if certificate or OCSP decoding or cryptographic verification fails
   */
  @Dann("die aktuelle OCSP-Antwort für Zertifikat {tigerResolvedString} mit Ausstellerzertifikat {tigerResolvedString} ist gültig und meldet {string}")
  public void currentOcspResponseHasValidStatus(
      String certificate, String issuerCertificate, String status) throws Exception {
    var request = RbelMessageRetriever.getInstance().getCurrentRequest();
    var messages = RecordedMessages.snapshot();
    var response = new TimingGlue().findResponseForRequest(messages, request);
    Assertions.assertThat(response).as("correlated OCSP response").isNotNull();
    verifySignedStatus(currentResponseBody(),
        CertificateMaterial.parseCertificate(certificate, "certificate"),
        CertificateMaterial.parseCertificate(issuerCertificate, "issuer certificate"),
        status, TimingGlue.responseReceivedAt(messages, response));
  }

  /**
   * Checks a certificate-bound OCSP status, authorized signature and validity at receipt.
   *
   * @param bytes encoded OCSP response
   * @param certificate expected certificate
   * @param issuer certificate issuer
   * @param status expected status, {@code good} or {@code revoked}
   * @param received time the response was received
   * @throws Exception if parsing or cryptographic verification fails
   */
  static void verifySignedStatus(byte[] bytes, X509Certificate certificate,
      X509Certificate issuer, String status, Instant received) throws Exception {
    Assertions.assertThat(status).isIn("good", "revoked");
    var outer = new OCSPResp(bytes);
    Assertions.assertThat(outer.getStatus()).as("OCSP response status").isZero();
    Assertions.assertThat(outer.getResponseObject()).isInstanceOf(BasicOCSPResp.class);
    var basic = (BasicOCSPResp) outer.getResponseObject();
    var expected = buildCertificateId(certificate, issuer);
    var matching = Arrays.stream(basic.getResponses())
        .filter(item -> certificateIdsMatch(item.getCertID(), expected)).toList();
    Assertions.assertThat(matching).as("certificate-bound OCSP status").hasSize(1);
    var item = matching.getFirst();
    Assertions.assertThat(item.getThisUpdate()).as("thisUpdate").isNotNull();
    Assertions.assertThat(item.getThisUpdate().toInstant()).isBeforeOrEqualTo(received);
    Assertions.assertThat(item.getNextUpdate()).as("nextUpdate").isNotNull();
    Assertions.assertThat(item.getNextUpdate().toInstant()).isAfter(received);
    if (status.equals("good")) {
      Assertions.assertThat(item.getCertStatus()).as("GOOD status").isNull();
    } else {
      Assertions.assertThat(item.getCertStatus()).isInstanceOf(RevokedStatus.class);
      Assertions.assertThat(((RevokedStatus) item.getCertStatus()).getRevocationTime().toInstant())
          .isBeforeOrEqualTo(received);
    }
    var candidates = new ArrayList<X509Certificate>();
    candidates.add(issuer);
    for (var holder : basic.getCerts()) {
      candidates.add(new org.bouncycastle.cert.jcajce.JcaX509CertificateConverter()
          .getCertificate(holder));
    }
    boolean verified = false;
    for (var signer : candidates) {
      if (isAuthorizedOcspSigner(signer, issuer)) {
        try {
          signer.checkValidity(java.util.Date.from(received));
          if (basic.isSignatureValid(new JcaContentVerifierProviderBuilder().build(signer))) {
            verified = true;
            break;
          }
        } catch (GeneralSecurityException | OCSPException | OperatorCreationException e) {
          continue;
        }
      }
    }
    Assertions.assertThat(verified).as("authorized valid OCSP signature").isTrue();
  }

  /**
   * Verifies that the OCSP response correlated with the current request contains a future {@code nextUpdate} value for the requested
   * certificate.
   */
  @Dann("die aktuelle OCSP-Antwort enthält ein zukünftiges NextUpdate")
  @Then("the current OCSP response contains a future NextUpdate")
  public void currentOcspResponseContainsFutureNextUpdate() {
    var matchingResponses = matchingSingleResponsesForCurrentRequest();
    Assertions.assertThat(matchingResponses)
        .as("OCSP response entries matching the current request")
        .hasSize(1);

    var nextUpdate = matchingResponses.getFirst().getNextUpdate();
    Assertions.assertThat(nextUpdate)
        .as("nextUpdate of the OCSP response for the requested certificate")
        .isNotNull();
    var nextUpdateInstant = nextUpdate.toInstant();
    Assertions.assertThat(nextUpdateInstant)
        .as("nextUpdate of the OCSP response for the requested certificate")
        .isAfter(Instant.now());
    lastVerifiedOcspNextUpdate = nextUpdateInstant;
    ReportAttachments.addText("Verified OCSP nextUpdate", nextUpdateInstant.toString());
  }

  /**
   * Waits until the {@code nextUpdate} value remembered by the latest successful OCSP response
   * assertion has expired by the requested safety margin.
   *
   * @param expirationMarginSeconds seconds to wait beyond {@code nextUpdate}
   */
  @Dann("warte bis das NextUpdate der zuletzt geprüften OCSP-Antwort seit {int} Sekunden abgelaufen ist")
  @Then("wait until the NextUpdate of the last verified OCSP response has been expired for {int} seconds")
  public void waitUntilLastVerifiedOcspNextUpdateExpired(int expirationMarginSeconds) {
    Assertions.assertThat(expirationMarginSeconds)
        .as("OCSP nextUpdate expiration safety margin in seconds")
        .isGreaterThanOrEqualTo(0);
    Assertions.assertThat(lastVerifiedOcspNextUpdate)
        .as("nextUpdate remembered from the last verified OCSP response")
        .isNotNull();

    var target = lastVerifiedOcspNextUpdate.plusSeconds(expirationMarginSeconds);
    var waitDuration = remainingWaitDuration(target, Instant.now());
    Assertions.assertThat(waitDuration)
        .as("remaining wait until OCSP nextUpdate plus safety margin")
        .isLessThanOrEqualTo(MAX_OCSP_NEXT_UPDATE_WAIT);
    ReportAttachments.addText(
        "OCSP nextUpdate expiry wait",
        "nextUpdate=" + lastVerifiedOcspNextUpdate + System.lineSeparator()
            + "target=" + target + System.lineSeparator()
            + "waitMillis=" + waitDuration.toMillis());

    sleepUntil(target);
  }

  /**
   * Calculates the non-negative time remaining until a target instant.
   *
   * @param target target instant
   * @param now current instant
   * @return zero when the target has already passed, otherwise the remaining duration
   */
  public static Duration remainingWaitDuration(Instant target, Instant now) {
    Objects.requireNonNull(target, "target must not be null");
    Objects.requireNonNull(now, "now must not be null");
    var remaining = Duration.between(now, target);
    return remaining.isNegative() ? Duration.ZERO : remaining;
  }

  /**
   * Sleeps until the target instant while preserving interruption state.
   *
   * @param target target instant
   */
  private static void sleepUntil(Instant target) {
    var remaining = remainingWaitDuration(target, Instant.now());
    while (!remaining.isZero()) {
      try {
        Thread.sleep(remaining);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AssertionError("Interrupted while waiting for the OCSP nextUpdate to expire.", e);
      }
      remaining = remainingWaitDuration(target, Instant.now());
    }
  }

  /**
   * Verifies that the OCSP response correlated with the current request reports the requested certificate as revoked.
   */
  @Dann("die aktuelle OCSP-Antwort meldet das angefragte Zertifikat als widerrufen")
  @Then("the current OCSP response reports the requested certificate as revoked")
  public void currentOcspResponseReportsRequestedCertificateRevoked() {
    var matchingResponses = matchingSingleResponsesForCurrentRequest();
    Assertions.assertThat(matchingResponses)
        .as("OCSP response entries matching the current request")
        .hasSize(1);
    Assertions.assertThat(matchingResponses.getFirst().getCertStatus())
        .as("OCSP certificate status for the requested certificate")
        .isInstanceOf(RevokedStatus.class);
  }

  /**
   * Verifies that any recorded OCSP exchange queried a selected chain certificate and reported it
   * as revoked.
   *
   * @param position zero-based certificate position in the PEM chain
   * @param certificate certificate-chain fixture
   */
  @Dann(
      "wurde eine OCSP-Anfrage für das Zertifikat an Position {int} der Zertifikatskette {tlsServerCertificate} mit widerrufen beantwortet")
  @Then(
      "an OCSP request for certificate at position {int} of chain {tlsServerCertificate} was answered as revoked")
  public void recordedOcspExchangeReportsChainPositionRevoked(
      int position, TlsServerCertificates certificate) {
    try {
      var chain = readCertificateChain(certificate);
      Assertions.assertThat(position)
          .as("certificate position in fixture chain")
          .isBetween(0, chain.size() - 2);
      var expectedCertificateId = buildCertificateId(chain.get(position), chain.get(position + 1));
      var messages = RecordedMessages.snapshot();
      Assertions.assertThat(messages).as("recorded RBEL messages").isNotNull().isNotEmpty();

      var matchingRequests = messages.stream()
          .filter(this::isOcspRequest)
          .filter(
              request ->
                  extractOcspRequestBytes(request)
                      .map(OcspRequestSteps::extractCertificateIds)
                      .stream()
                      .flatMap(List::stream)
                      .anyMatch(actual -> certificateIdsMatch(actual, expectedCertificateId)))
          .toList();
      Assertions.assertThat(matchingRequests)
          .as("OCSP requests for chain position %d", position)
          .isNotEmpty();

      var responses = matchingRequests.stream()
          .map(this::responseBodyForRequest)
          .map(this::parseBasicOcspResponse)
          .flatMap(response -> Arrays.stream(response.getResponses()))
          .filter(response -> certificateIdsMatch(response.getCertID(), expectedCertificateId))
          .toList();
      Assertions.assertThat(responses)
          .as("OCSP response entries for chain position %d", position)
          .isNotEmpty()
          .allMatch(response -> response.getCertStatus() instanceof RevokedStatus);
    } catch (IOException
        | GeneralSecurityException
        | OCSPException
        | OperatorCreationException e) {
      throw new AssertionError("Failed to evaluate recorded OCSP exchanges.", e);
    }
  }

  /** Verifies that the response to the selected CRL request is a currently valid X.509 CRL. */
  @Dann("enthält die aktuelle Antwort eine gültige CRL mit zukünftigem NextUpdate")
  @Then("the current response contains a valid CRL with a future NextUpdate")
  public void currentResponseContainsValidCrlWithFutureNextUpdate() {
    try (var inputStream = new java.io.ByteArrayInputStream(currentResponseBody())) {
      var crl = (X509CRL) CertificateFactory.getInstance("X.509").generateCRL(inputStream);
      Assertions.assertThat(crl.getThisUpdate().toInstant())
          .as("CRL thisUpdate")
          .isBeforeOrEqualTo(Instant.now());
      Assertions.assertThat(crl.getNextUpdate())
          .as("CRL nextUpdate")
          .isNotNull();
      Assertions.assertThat(crl.getNextUpdate().toInstant())
          .as("CRL nextUpdate")
          .isAfter(Instant.now());
    } catch (GeneralSecurityException | IOException e) {
      throw new AssertionError("Failed to parse the correlated CRL response.", e);
    }
  }

  /**
   * Verifies that the OCSP response validity interval has already ended.
   */
  @Dann("ist die aktuelle OCSP-Antwort abgelaufen")
  @Then("the current OCSP response is expired")
  public void currentOcspResponseIsExpired() {
    var matchingResponses = matchingSingleResponsesForCurrentRequest();
    Assertions.assertThat(matchingResponses)
        .as("OCSP response entries matching the current request")
        .hasSize(1);
    var response = matchingResponses.getFirst();
    Assertions.assertThat(response.getThisUpdate().toInstant())
        .as("thisUpdate of expired OCSP response")
        .isBefore(Instant.now());
    Assertions.assertThat(response.getNextUpdate())
        .as("nextUpdate of expired OCSP response")
        .isNotNull();
    Assertions.assertThat(response.getNextUpdate().toInstant())
        .as("nextUpdate of expired OCSP response")
        .isBefore(Instant.now());
  }

  /**
   * Verifies that the OCSP response does not contain an entry for the requested certificate.
   */
  @Dann("passt die aktuelle OCSP-Antwort nicht zum angefragten Zertifikat")
  @Then("the current OCSP response does not match the requested certificate")
  public void currentOcspResponseDoesNotMatchRequestedCertificate() {
    Assertions.assertThat(matchingSingleResponsesForCurrentRequest())
        .as("OCSP response entries matching the current request")
        .isEmpty();
  }

  /**
   * Verifies that the signature of the correlated OCSP response is cryptographically invalid.
   */
  @Dann("hat die aktuelle OCSP-Antwort eine ungültige Signatur")
  @Then("the current OCSP response has an invalid signature")
  public void currentOcspResponseHasInvalidSignature() {
    var basicResponse = currentBasicOcspResponse();
    var responderCertificates = basicResponse.getCerts();
    Assertions.assertThat(responderCertificates)
        .as("embedded OCSP responder certificate")
        .hasSize(1);
    try {
      var verifier = new JcaContentVerifierProviderBuilder()
          .build(responderCertificates[0]);
      Assertions.assertThat(basicResponse.isSignatureValid(verifier))
          .as("OCSP response signature validity")
          .isFalse();
    } catch (GeneralSecurityException | OperatorCreationException | OCSPException e) {
      throw new AssertionError("Failed to verify the OCSP response signature.", e);
    }
  }

  /**
   * Verifies that the correlated OCSP response has a valid signature, but that its signer is
   * neither the certificate issuer nor an issuer-authorized delegated OCSP signer.
   */
  @Dann("hat die aktuelle OCSP-Antwort eine gültige Signatur von einem nicht autorisierten Signierer")
  @Then("the current OCSP response has a valid signature from an unauthorized signer")
  public void currentOcspResponseHasValidSignatureFromUnauthorizedSigner() {
    var basicResponse = currentBasicOcspResponse();
    var responderCertificates = basicResponse.getCerts();
    Assertions.assertThat(responderCertificates)
        .as("embedded OCSP responder certificate")
        .hasSize(1);
    try {
      var signer = parseCertificate(responderCertificates[0].getEncoded());
      var issuer = readCertificate(
          TlsTestToolCertificateFixtures.resolveCertificateOrKeyPath(DEFAULT_CA_CERTIFICATE.toString()));
      var verifier = new JcaContentVerifierProviderBuilder().build(responderCertificates[0]);
      Assertions.assertThat(basicResponse.isSignatureValid(verifier))
          .as("OCSP response signature validity")
          .isTrue();
      Assertions.assertThat(isAuthorizedOcspSigner(signer, issuer))
          .as("authorization of embedded OCSP signer for the requested certificate issuer")
          .isFalse();
    } catch (IOException | GeneralSecurityException | OperatorCreationException | OCSPException e) {
      throw new AssertionError("Failed to validate the OCSP response signer.", e);
    }
  }

  /**
   * Verifies that an encoded OCSP request contains a CertID matching the given certificate fixture.
   *
   * @param ocspRequestBytes DER-encoded OCSP request
   * @param certificate expected TLS test tool certificate fixture
   */
  public void assertOcspRequestContainsCertificateId(byte[] ocspRequestBytes, TlsServerCertificates certificate) {
    var expectedCertificateId = buildExpectedCertificateId(certificate);
    assertOcspRequestContainsCertificateId(ocspRequestBytes, expectedCertificateId,
        certificate.getCertificateId());
  }

  /**
   * Verifies that an encoded OCSP request contains a CertID matching the given leaf and issuer certificates.
   *
   * @param ocspRequestBytes DER-encoded OCSP request
   * @param certificate certificate whose revocation status is requested
   * @param issuerCertificate issuer certificate for the requested certificate
   * @param certificateDescription readable certificate description for assertion diagnostics
   */
  public void assertOcspRequestContainsCertificateId(
      byte[] ocspRequestBytes,
      X509Certificate certificate,
      X509Certificate issuerCertificate,
      String certificateDescription) {
    try {
      var expectedCertificateId = buildCertificateId(certificate, issuerCertificate);
      assertOcspRequestContainsCertificateId(ocspRequestBytes, expectedCertificateId,
          certificateDescription);
    } catch (OCSPException | OperatorCreationException | java.security.cert.CertificateEncodingException e) {
      throw new AssertionError(
          "Failed to build expected OCSP CertID for " + certificateDescription + ".", e);
    }
  }

  /**
   * Verifies that an encoded OCSP request contains the expected CertID.
   *
   * @param ocspRequestBytes DER-encoded OCSP request
   * @param expectedCertificateId expected OCSP CertID
   * @param certificateDescription readable certificate description for assertion diagnostics
   */
  private void assertOcspRequestContainsCertificateId(
      byte[] ocspRequestBytes,
      CertificateID expectedCertificateId,
      String certificateDescription) {
    var actualCertificateIds = extractCertificateIds(ocspRequestBytes);

    Assertions
        .assertThat(actualCertificateIds)
        .withFailMessage(
            "OCSP request did not contain CertID for certificate %s. Expected %s but found %s.",
            certificateDescription,
            formatCertificateId(expectedCertificateId),
            actualCertificateIds.stream().map(OcspRequestSteps::formatCertificateId).toList())
        .anyMatch(actualCertificateId -> certificateIdsMatch(actualCertificateId, expectedCertificateId));
  }

  /**
   * Extracts the OCSP request bytes from the currently selected RBEL request.
   *
   * @return DER-encoded OCSP request bytes
   */
  private byte[] currentOcspRequestBytes() {
    var currentRequest = RbelMessageRetriever.getInstance().getCurrentRequest();
    if (currentRequest == null) {
      throw new AssertionError("No current request message found.");
    }

    return extractOcspRequestBytes(currentRequest)
        .orElseThrow(() -> new AssertionError("The current request does not contain an OCSP request body."));
  }

  /**
   * Extracts the DER encoded OCSP request from an RBEL HTTP request.
   *
   * @param request RBEL request element
   * @return OCSP request bytes if present
   */
  Optional<byte[]> extractOcspRequestBytes(RbelElement request) {
    return extractRequestBody(request)
        .filter(body -> body.length > 0)
        .or(() -> extractGetRequestOcspBody(request));
  }

  /**
   * Extracts the raw body bytes from an RBEL request.
   *
   * @param request RBEL request element
   * @return request body bytes if RBEL exposes a body node
   */
  private Optional<byte[]> extractRequestBody(RbelElement request) {
    return request.findRbelPathMembers("$.body")
        .stream()
        .map(RbelElement::getRawContent)
        .filter(Objects::nonNull)
        .filter(body -> body.length > 0)
        .findFirst();
  }

  /**
   * Extracts an OCSP request encoded in an HTTP GET request path.
   *
   * @param request RBEL request element
   * @return decoded OCSP request bytes if the current path carries one
   */
  private Optional<byte[]> extractGetRequestOcspBody(RbelElement request) {
    return request.findRbelPathMembers("$.path")
        .stream()
        .map(RbelElement::getRawStringContent)
        .filter(Objects::nonNull)
        .map(String::trim)
        .flatMap(path -> decodeOcspRequestFromPath(path).stream())
        .findFirst();
  }

  /**
   * Returns the single-response entries whose certificate IDs match the currently selected OCSP request.
   *
   * @return matching OCSP response entries
   */
  private List<SingleResp> matchingSingleResponsesForCurrentRequest() {
    var requestedCertificateIds = extractCertificateIds(currentOcspRequestBytes());
    return Arrays.stream(currentBasicOcspResponse().getResponses())
        .filter(response -> requestedCertificateIds.stream()
            .anyMatch(requestedCertificateId -> certificateIdsMatch(response.getCertID(), requestedCertificateId)))
        .toList();
  }

  /**
   * Parses the response correlated with the current OCSP request as a basic OCSP response.
   *
   * @return parsed basic OCSP response
   */
  private BasicOCSPResp currentBasicOcspResponse() {
    try {
      var responseObject = new OCSPResp(currentResponseBody()).getResponseObject();
      if (responseObject instanceof BasicOCSPResp basicOcspResponse) {
        return basicOcspResponse;
      }
      throw new AssertionError("The correlated OCSP response does not contain a basic OCSP response.");
    } catch (IOException | OCSPException e) {
      throw new AssertionError("Failed to parse the correlated OCSP response.", e);
    }
  }

  /**
   * Returns the raw body of the response correlated with the currently selected request.
   *
   * @return response body bytes
   */
  private byte[] currentResponseBody() {
    var retriever = RbelMessageRetriever.getInstance();
    var currentRequest = retriever.getCurrentRequest();
    if (currentRequest == null) {
      throw new AssertionError("No current OCSP request message found.");
    }

    var messages = RecordedMessages.snapshot();
    if (messages == null || messages.isEmpty()) {
      throw new AssertionError("No recorded messages found for the current OCSP request.");
    }
    return responseBodyForRequest(currentRequest);
  }

  /**
   * Returns the response body correlated with an RBEL request.
   *
   * @param request recorded request
   * @return correlated raw response body
   */
  private byte[] responseBodyForRequest(RbelElement request) {
    var messages = RecordedMessages.snapshot();
    var response = new TimingGlue().findResponseForRequest(messages, request);
    if (response == null) {
      throw new AssertionError("No correlated OCSP response found for request " + request.getUuid() + ".");
    }

    return response.findRbelPathMembers("$.body")
        .stream()
        .map(RbelElement::getRawContent)
        .filter(Objects::nonNull)
        .filter(body -> body.length > 0)
        .findFirst()
        .orElseThrow(() -> new AssertionError("The correlated response does not contain a response body."));
  }

  /**
   * Checks whether an RBEL element is an HTTP request to an OCSP endpoint.
   *
   * @param message recorded RBEL element
   * @return {@code true} for OCSP requests
   */
  private boolean isOcspRequest(RbelElement message) {
    return message.findRbelPathMembers("$.path").stream()
        .map(RbelElement::getRawStringContent)
        .filter(Objects::nonNull)
        .map(String::trim)
        .anyMatch(path -> path.startsWith("/ocsp/") || path.startsWith("/ecc-ocsp"));
  }

  /**
   * Parses a DER-encoded OCSP response body.
   *
   * @param responseBody encoded OCSP response
   * @return basic response object
   */
  private BasicOCSPResp parseBasicOcspResponse(byte[] responseBody) {
    try {
      var responseObject = new OCSPResp(responseBody).getResponseObject();
      if (responseObject instanceof BasicOCSPResp basicResponse) {
        return basicResponse;
      }
      throw new AssertionError("The correlated OCSP response does not contain a basic response.");
    } catch (IOException | OCSPException e) {
      throw new AssertionError("Failed to parse a correlated OCSP response.", e);
    }
  }

  /**
   * Decodes a Base64-encoded OCSP request from the final HTTP path segment.
   *
   * @param path request path
   * @return decoded OCSP request bytes if the path contains an encoded request segment
   */
  private Optional<byte[]> decodeOcspRequestFromPath(String path) {
    var lastSlash = path.lastIndexOf('/');
    if (lastSlash < 0 || lastSlash + 1 >= path.length()) {
      return Optional.empty();
    }
    var encodedRequest = URLDecoder.decode(path.substring(lastSlash + 1), StandardCharsets.UTF_8);
    try {
      return Optional.of(Base64.getDecoder().decode(encodedRequest));
    } catch (IllegalArgumentException e) {
      try {
        return Optional.of(Base64.getUrlDecoder().decode(encodedRequest));
      } catch (IllegalArgumentException ignored) {
        return Optional.empty();
      }
    }
  }

  /**
   * Builds the OCSP CertID expected for a TLS test tool certificate fixture.
   *
   * @param certificate expected certificate fixture
   * @return OCSP CertID for the leaf certificate
   */
  private CertificateID buildExpectedCertificateId(TlsServerCertificates certificate) {
    try {
      var certificateChain = readCertificateChain(certificate);
      var leafCertificate = certificateChain.getFirst();
      var issuerCertificate = resolveIssuerCertificate(certificate, certificateChain, leafCertificate);
      return buildCertificateId(leafCertificate, issuerCertificate);
    } catch (IOException | GeneralSecurityException | OCSPException | OperatorCreationException e) {
      throw new AssertionError("Failed to build expected OCSP CertID for " + certificate.getCertificateId() + ".", e);
    }
  }

  /**
   * Resolves the issuer certificate required for OCSP CertID construction.
   *
   * @param certificate      certificate fixture descriptor
   * @param certificateChain parsed certificate chain in file order
   * @param leafCertificate  leaf certificate whose revocation status is requested
   * @return issuer certificate
   * @throws IOException              if the configured CA certificate cannot be read
   * @throws GeneralSecurityException if the configured CA certificate cannot be parsed
   */
  private X509Certificate resolveIssuerCertificate(
      TlsServerCertificates certificate,
      List<X509Certificate> certificateChain,
      X509Certificate leafCertificate)
      throws IOException, GeneralSecurityException {
    if (certificateChain.size() > 1) {
      var issuerCertificate = certificateChain.get(1);
      assertIssuerMatches(certificate, leafCertificate, issuerCertificate);
      return issuerCertificate;
    }

    var configuredCaCertificate = readCertificate(TlsTestToolCertificateFixtures.resolveCaCertificatePath());
    assertIssuerMatches(certificate, leafCertificate, configuredCaCertificate);
    return configuredCaCertificate;
  }

  /**
   * Verifies that the selected issuer certificate really issued the leaf certificate.
   *
   * @param certificate       certificate fixture descriptor
   * @param leafCertificate   leaf certificate whose revocation status is requested
   * @param issuerCertificate expected issuer certificate
   */
  private void assertIssuerMatches(
      TlsServerCertificates certificate,
      X509Certificate leafCertificate,
      X509Certificate issuerCertificate) {
    if (!leafCertificate.getIssuerX500Principal().equals(issuerCertificate.getSubjectX500Principal())) {
      throw new AssertionError(
          "Certificate fixture "
              + certificate.getCertificateId()
              + " is leaf-only and its issuer does not match the configured TLS test tool CA. "
              + "Add the issuing CA to the certificate fixture before using it for OCSP CertID assertions.");
    }
  }

  /**
   * Builds the OCSP CertID for the given leaf and issuer certificates.
   *
   * @param leafCertificate certificate whose revocation status is requested
   * @param issuerCertificate issuer certificate for the leaf
   * @return OCSP CertID
   * @throws OCSPException if CertID creation fails
   * @throws OperatorCreationException if the digest calculator cannot be created
   * @throws java.security.cert.CertificateEncodingException if the issuer
   *         certificate cannot be encoded
   */
  static CertificateID buildCertificateId(X509Certificate leafCertificate, X509Certificate issuerCertificate)
      throws OCSPException, OperatorCreationException, java.security.cert.CertificateEncodingException {
    var digestCalculator = new JcaDigestCalculatorProviderBuilder()
        .build()
        .get(CertificateID.HASH_SHA1);
    return new CertificateID(
        digestCalculator,
        new JcaX509CertificateHolder(issuerCertificate),
        leafCertificate.getSerialNumber());
  }

  /**
   * Extracts all requested CertIDs from an encoded OCSP request.
   *
   * @param ocspRequestBytes DER-encoded OCSP request
   * @return requested CertIDs
   */
  static List<CertificateID> extractCertificateIds(byte[] ocspRequestBytes) {
    try {
      return Arrays.stream(new OCSPReq(ocspRequestBytes).getRequestList())
          .map(Req::getCertID)
          .toList();
    } catch (IOException e) {
      throw new AssertionError("Failed to parse OCSP request.", e);
    }
  }

  /**
   * Compares two OCSP CertIDs by hash algorithm, issuer hashes, and serial number.
   *
   * @param actual actual CertID from request
   * @param expected expected CertID from certificate fixture
   * @return {@code true} if the CertIDs identify the same certificate
   */
  static boolean certificateIdsMatch(CertificateID actual, CertificateID expected) {
    return actual.getHashAlgOID().equals(expected.getHashAlgOID())
        && Arrays.equals(actual.getIssuerNameHash(), expected.getIssuerNameHash())
        && Arrays.equals(actual.getIssuerKeyHash(), expected.getIssuerKeyHash())
        && actual.getSerialNumber().equals(expected.getSerialNumber());
  }

  /**
   * Formats an OCSP CertID for assertion diagnostics.
   *
   * @param certificateId CertID to format
   * @return readable CertID summary
   */
  static String formatCertificateId(CertificateID certificateId) {
    return "hashAlg=%s, issuerNameHash=%s, issuerKeyHash=%s, serial=%s".formatted(
        certificateId.getHashAlgOID().getId(),
        HexFormat.of().formatHex(certificateId.getIssuerNameHash()),
        HexFormat.of().formatHex(certificateId.getIssuerKeyHash()),
        formatSerial(certificateId.getSerialNumber()));
  }

  /**
   * Formats a certificate serial number in hexadecimal.
   *
   * @param serial serial number
   * @return hexadecimal serial number
   */
  private static String formatSerial(BigInteger serial) {
    return serial.toString(16);
  }

  /**
   * Reads all certificates from a mapped TLS test tool certificate fixture.
   *
   * @param certificate certificate fixture descriptor
   * @return parsed certificate chain in file order
   * @throws IOException if the certificate file cannot be read
   * @throws GeneralSecurityException if the certificate file cannot be parsed
   */
  private List<X509Certificate> readCertificateChain(TlsServerCertificates certificate)
      throws IOException, GeneralSecurityException {
    return readCertificateChain(TlsTestToolCertificateFixtures.resolveCertificateOrKeyPath(certificate.getRelativePath()));
  }

  /**
   * Reads all certificates from a fixture path.
   *
   * @param certificatePath certificate fixture path
   * @return parsed certificate chain in file order
   * @throws IOException if the certificate file cannot be read
   * @throws GeneralSecurityException if the certificate file cannot be parsed
   */
  private List<X509Certificate> readCertificateChain(Path certificatePath)
      throws IOException, GeneralSecurityException {
    try (var inputStream = Files.newInputStream(certificatePath)) {
      return CertificateFactory.getInstance("X.509")
          .generateCertificates(inputStream)
          .stream()
          .map(X509Certificate.class::cast)
          .collect(ArrayList::new, List::add, List::addAll);
    }
  }

  /**
   * Reads the first certificate from a fixture path.
   *
   * @param certificatePath certificate fixture path
   * @return parsed certificate
   * @throws IOException if the certificate file cannot be read
   * @throws GeneralSecurityException if the certificate file cannot be parsed
   */
  private X509Certificate readCertificate(Path certificatePath)
      throws IOException, GeneralSecurityException {
    var certificates = readCertificateChain(certificatePath);
    if (certificates.isEmpty()) {
      throw new AssertionError("Certificate fixture contains no certificate: " + certificatePath);
    }
    return certificates.getFirst();
  }

  /**
   * Parses one DER-encoded X.509 certificate.
   *
   * @param encodedCertificate DER-encoded certificate
   * @return parsed certificate
   * @throws GeneralSecurityException if the certificate cannot be parsed
   */
  private X509Certificate parseCertificate(byte[] encodedCertificate)
      throws GeneralSecurityException {
    try (var inputStream = new java.io.ByteArrayInputStream(encodedCertificate)) {
      return (X509Certificate) CertificateFactory.getInstance("X.509")
          .generateCertificate(inputStream);
    } catch (IOException e) {
      throw new GeneralSecurityException("Failed to close certificate input stream.", e);
    }
  }

  /**
   * Checks whether an OCSP response signer is the issuer or an issuer-signed delegated responder
   * with the id-kp-OCSPSigning extended key usage.
   *
   * @param signer response signer certificate
   * @param issuer issuer of the certificate whose status was requested
   * @return whether the signer is authorized for the issuer
   */
  private static boolean isAuthorizedOcspSigner(X509Certificate signer, X509Certificate issuer) {
    if (signer.equals(issuer)) {
      return true;
    }
    try {
      signer.verify(issuer.getPublicKey());
      var extendedKeyUsage = signer.getExtendedKeyUsage();
      return signer.getIssuerX500Principal().equals(issuer.getSubjectX500Principal())
          && extendedKeyUsage != null
          && extendedKeyUsage.contains("1.3.6.1.5.5.7.3.9");
    } catch (GeneralSecurityException e) {
      return false;
    }
  }
}
