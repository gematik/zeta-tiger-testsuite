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

import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.zeta.services.ZetaDeploymentConfigurationService;
import de.gematik.zeta.services.ZetaDeploymentConfigurationServiceFactory;
import de.gematik.zeta.services.model.ZetaDeploymentDetails;
import de.gematik.zeta.services.model.ZetaDisableAslRequest;
import de.gematik.zeta.services.model.ZetaEnableAslRequest;
import de.gematik.zeta.services.model.ZetaPoppTokenToggleRequest;
import io.cucumber.java.de.Gegebensei;
import io.cucumber.java.de.Und;
import io.cucumber.java.en.And;
import io.cucumber.java.en.Given;
import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;

/**
 * Cucumber step definitions for modifications of the Zeta Guard deployment.
 */
@Slf4j
public class DeploymentManipulationSteps {

  private static final int MAX_KEY_DEPTH = 20;

  private final ZetaDeploymentConfigurationService service = ZetaDeploymentConfigurationServiceFactory.getInstance();

  /**
   * Cucumber step to disable the Additional Security Layer in a Zeta Guard deployment.
   *
   * @throws AssertionError if any exception occurred during setup or execution; added as wrapper for consistency
   */
  @Und("deaktiviere den Additional Security Layer im Zeta Deployment")
  @And("deactivate the Additional Security Layer in Zeta deployment")
  public void disableAsl() throws AssertionError {

    ZetaDeploymentDetails details = getDeploymentDetails();
    ZetaDisableAslRequest request = getDisableAslRequest();
    try {
      service.disableAsl(details, request);
    } catch (TimeoutException te) {
      throw new AssertionError("Timeout occurred while waiting for command or system state", te);
    } catch (InterruptedException ie) {
      throw new AssertionError("Command execution was interrupted", ie);
    } catch (IOException ioe) {
      throw new AssertionError("An error occurred handling temporary files", ioe);
    } catch (Exception e) {
      throw new AssertionError("An unexpected error occurred", e);
    }
  }

  /**
   * Cucumber step to enable the Additional Security Layer in a Zeta Guard
   * deployment.
   *
   * @throws AssertionError if any exception occurred during setup or execution; added as wrapper for consistency
   */
  @Gegebensei("aktiviere den Additional Security Layer im Zeta Deployment")
  @Given("activate the Additional Security Layer in Zeta deployment")
  public void enableAsl() throws AssertionError {
    ZetaDeploymentDetails details = getDeploymentDetails();
    ZetaEnableAslRequest request = getEnableAslRequest();
    try {
      service.enableAsl(details, request);
    } catch (TimeoutException te) {
      throw new AssertionError("Timeout occurred while waiting for command or system state", te);
    } catch (InterruptedException ie) {
      throw new AssertionError("Command execution was interrupted", ie);
    } catch (IOException ioe) {
      throw new AssertionError("An error occurred handling temporary files", ioe);
    } catch (Exception e) {
      throw new AssertionError("An unexpected error occurred", e);
    }
  }

  /**
   * Cucumber step to disable the PoPP token verification for a route in a ZETA Guard deployment.
   *
   * @throws AssertionError if any exception occurred during setup or execution; added as wrapper for consistency
   */
  @Und("deaktiviere die PoPP Token Verifikation für die Route {tigerResolvedString} im ZETA Deployment")
  @And("deactivate PoPP token verification for route {tigerResolvedString} in ZETA deployment")
  public void disablePoppTokenVerification(String targetRoute) throws AssertionError {

    ZetaDeploymentDetails details = getDeploymentDetails();
    ZetaPoppTokenToggleRequest request = getPoppTokenRequest();
    try {
      service.disablePoppVerification(details, request, targetRoute);
    } catch (TimeoutException te) {
      throw new AssertionError("Timeout occurred while waiting for command or system state", te);
    } catch (InterruptedException ie) {
      throw new AssertionError("Command execution was interrupted", ie);
    } catch (IOException ioe) {
      throw new AssertionError("An error occurred handling temporary files", ioe);
    } catch (Exception e) {
      throw new AssertionError("An unexpected error occurred", e);
    }
  }

  /**
   * Cucumber step to enable the PoPP token verification for a route in a ZETA Guard deployment.
   *
   * @throws AssertionError if any exception occurred during setup or execution; added as wrapper for consistency
   */
  @Und("aktiviere die PoPP Token Verifikation für die Route {tigerResolvedString} im ZETA Deployment")
  @And("activate PoPP token verification for route {tigerResolvedString} in ZETA deployment")
  public void enablePoppTokenVerification(String targetRoute) throws AssertionError {

    ZetaDeploymentDetails details = getDeploymentDetails();
    ZetaPoppTokenToggleRequest request = getPoppTokenRequest();
    try {
      service.enablePoppVerification(details, request, targetRoute);
    } catch (TimeoutException te) {
      throw new AssertionError("Timeout occurred while waiting for command or system state", te);
    } catch (InterruptedException ie) {
      throw new AssertionError("Command execution was interrupted", ie);
    } catch (IOException ioe) {
      throw new AssertionError("An error occurred handling temporary files", ioe);
    } catch (Exception e) {
      throw new AssertionError("An unexpected error occurred", e);
    }
  }

  private ZetaDeploymentDetails getDeploymentDetails() throws AssertionError {
    String namespace = TigerGlobalConfiguration.readStringOptional("zetaDeploymentConfig.namespace")
        .orElseThrow(() -> new AssertionError("Missing variable: namespace"));
    String pepPodName = TigerGlobalConfiguration.readStringOptional("zetaDeploymentConfig.pep.podName")
        .orElseThrow(() -> new AssertionError("Missing variable: pep.podName"));

    String nginxConfigMapName = TigerGlobalConfiguration.readStringOptional("zetaDeploymentConfig.pep.nginx.configMapName")
        .orElseThrow(() -> new AssertionError("Missing variable: pep.nginx.configMapName"));
    String[] nginxConfigMapSegments = parseKeySegments("zetaDeploymentConfig.pep.nginx.keySegments");

    String wellKnownConfigMapName = TigerGlobalConfiguration.readStringOptional("zetaDeploymentConfig.pep.wellKnown.configMapName")
        .orElseThrow(() -> new AssertionError("Missing variable: pep.wellKnown.configMapName"));
    String[] wellKnownConfigMapSegments = parseKeySegments("zetaDeploymentConfig.pep.wellKnown.keySegments");

    return new ZetaDeploymentDetails(namespace, pepPodName,
        nginxConfigMapName, nginxConfigMapSegments,
        wellKnownConfigMapName, wellKnownConfigMapSegments);
  }

  private ZetaEnableAslRequest getEnableAslRequest() throws AssertionError {
    String nginxAslRegex = TigerGlobalConfiguration.readStringOptional("zetaDeploymentConfig.pep.nginx.asl.nginxConfigRegex")
        .orElseThrow(() -> new AssertionError("Missing variable: zetaDeploymentConfig.pep.nginx.asl.nginxConfigRegex"));

    String nginxAslAnchorRegex = TigerGlobalConfiguration.readStringOptional("zetaDeploymentConfig.pep.nginx.asl.nginxConfigAnchorRegex")
        .orElseThrow(() -> new AssertionError("Missing variable: zetaDeploymentConfig.pep.nginx.asl.nginxConfigAnchorRegex"));

    String nginxAslConfig = TigerGlobalConfiguration.readStringOptional("zetaDeploymentConfig.pep.nginx.asl.nginxConfig")
        .orElseThrow(() -> new AssertionError("Missing variable: zetaDeploymentConfig.pep.nginx.asl.nginxConfig"));

    String wellKnownAslRegex = TigerGlobalConfiguration.readStringOptional("zetaDeploymentConfig.pep.wellKnown.asl.resourceRegex")
        .orElseThrow(() -> new AssertionError("Missing variable: zetaDeploymentConfig.pep.wellKnown.asl.resourceRegex"));

    String wellKnownAslEnableValue = TigerGlobalConfiguration.readStringOptional("zetaDeploymentConfig.pep.wellKnown.asl.aslEnabledValue")
        .orElseThrow(() -> new AssertionError("Missing variable: zetaDeploymentConfig.pep.wellKnown.asl.aslEnabledValue"));

    return new ZetaEnableAslRequest(nginxAslRegex, nginxAslAnchorRegex, nginxAslConfig, wellKnownAslRegex, wellKnownAslEnableValue);
  }

  private ZetaDisableAslRequest getDisableAslRequest() throws AssertionError {
    String nginxAslRegex = TigerGlobalConfiguration.readStringOptional("zetaDeploymentConfig.pep.nginx.asl.nginxConfigRegex")
        .orElseThrow(() -> new AssertionError("Missing variable: zetaDeploymentConfig.pep.nginx.asl.nginxConfigRegex"));

    String wellKnownAslRegex = TigerGlobalConfiguration.readStringOptional("zetaDeploymentConfig.pep.wellKnown.asl.resourceRegex")
        .orElseThrow(() -> new AssertionError("Missing variable: zetaDeploymentConfig.pep.wellKnown.asl.resourceRegex"));

    String wellKnownAslDisableValue = TigerGlobalConfiguration.readStringOptional("zetaDeploymentConfig.pep.wellKnown.asl.aslDisabledValue")
        .orElseThrow(() -> new AssertionError("Missing variable: zetaDeploymentConfig.pep.wellKnown.asl.aslDisabledValue"));

    return new ZetaDisableAslRequest(nginxAslRegex, wellKnownAslRegex, wellKnownAslDisableValue);
  }

  private ZetaPoppTokenToggleRequest getPoppTokenRequest() {
    String nginxPoppRegex = TigerGlobalConfiguration.readStringOptional("zetaDeploymentConfig.pep.nginx.popp.nginxConfigRegex")
        .orElseThrow(() -> new AssertionError("Missing variable: zetaDeploymentConfig.pep.nginx.popp.nginxConfigRegex"));

    String nginxPoppEnabled = TigerGlobalConfiguration.readStringOptional("zetaDeploymentConfig.pep.nginx.popp.nginxConfigEnabled")
        .orElseThrow(() -> new AssertionError("Missing variable: zetaDeploymentConfig.pep.nginx.popp.nginxConfigEnabled"));

    String nginxPoppDisabled = TigerGlobalConfiguration.readStringOptional("zetaDeploymentConfig.pep.nginx.popp.nginxConfigDisabled")
        .orElseThrow(() -> new AssertionError("Missing variable: zetaDeploymentConfig.pep.nginx.popp.nginxConfigDisabled"));

    return new ZetaPoppTokenToggleRequest(
        nginxPoppRegex,
        nginxPoppEnabled,
        nginxPoppDisabled
    );
  }

  private String[] parseKeySegments(String baseKeyPath) throws AssertionError {
    // since the order of lists defined in tiger.yaml files is not retained this key-based custom order is required
    int i = 0;
    var result = new ArrayList<String>();
    while (i < MAX_KEY_DEPTH) {
      String segment = TigerGlobalConfiguration.readStringOptional(baseKeyPath + "." + i).orElse(null);
      if (segment == null) {
        return result.toArray(new String[0]);
      } else {
        result.add(segment);
      }
      i++;
    }
    throw new AssertionError("Max depth for configuration keys exceeded for key: " + baseKeyPath);
  }
}
