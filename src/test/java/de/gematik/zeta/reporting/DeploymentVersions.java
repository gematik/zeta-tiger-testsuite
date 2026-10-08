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

package de.gematik.zeta.reporting;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import net.serenitybdd.model.buildinfo.BuildInfo;
import net.serenitybdd.model.di.ModelInfrastructure;

/** Deployment container versions captured from Kubernetes. */
final class DeploymentVersions {

  static final String SERENITY_BUILD_INFO_FILE = "buildInfo.json";
  static final String ALLURE_ENVIRONMENT_FILE = "environment.properties";
  static final String ALLURE_PROPERTY_PREFIX = "deployment.version.";
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private final List<DeploymentVersion> versions;

  /**
   * Create an inventory.
   *
   * @param versions deployment container versions
   */
  private DeploymentVersions(List<DeploymentVersion> versions) {
    this.versions = List.copyOf(versions);
  }

  /**
   * Parse the pod inventory returned by {@code kubectl get pods --output json}.
   *
   * @param podsJson Kubernetes pod-list JSON
   * @return deployment container versions
   */
  static DeploymentVersions fromPodsJson(String podsJson) {
    try {
      var root = OBJECT_MAPPER.readTree(podsJson);
      var items = root.path("items");
      if (!items.isArray()) {
        throw new IllegalArgumentException("Kubernetes pod JSON does not contain an items array");
      }

      var parsedVersions = new ArrayList<DeploymentVersion>();
      for (var pod : items) {
        var namespace = pod.path("metadata").path("namespace").asText();
        var podName = pod.path("metadata").path("name").asText();
        appendContainerStatuses(
            parsedVersions, namespace, podName,
            pod.path("status").path("initContainerStatuses"), "init_container");
        appendContainerStatuses(
            parsedVersions, namespace, podName,
            pod.path("status").path("containerStatuses"), "container");
        appendContainerStatuses(
            parsedVersions, namespace, podName,
            pod.path("status").path("ephemeralContainerStatuses"), "ephemeral_container");
      }
      return new DeploymentVersions(parsedVersions);
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException("Unable to parse Kubernetes pod JSON", exception);
    }
  }

  /**
   * Append container status entries of one Kubernetes container type.
   *
   * @param versions target inventory
   * @param namespace pod namespace
   * @param pod pod name
   * @param statuses Kubernetes container-status array
   * @param containerType normalized container type
   */
  private static void appendContainerStatuses(
      List<DeploymentVersion> versions,
      String namespace,
      String pod,
      JsonNode statuses,
      String containerType) {
    if (!statuses.isArray()) {
      return;
    }
    for (var status : statuses) {
      versions.add(new DeploymentVersion(
          namespace,
          pod,
          status.path("name").asText(),
          containerType,
          status.path("image").asText(),
          status.path("imageID").asText()));
    }
  }

  /**
   * Write deployment versions using Serenity's native programmatic build information.
   *
   * @param serenityOutputDirectory Serenity output directory
   */
  void writeToSerenity(Path serenityOutputDirectory) {
    var configuration = ModelInfrastructure.getConfiguration();
    var previousOutputDirectory = configuration.getOutputDirectory();
    try {
      configuration.setOutputDirectory(serenityOutputDirectory.toFile());
      BuildInfo.clear();
      for (var version : versions) {
        BuildInfo.section(version.podName())
            .setProperty(version.containerName(), version.reportImage());
      }
    } finally {
      BuildInfo.clear();
      configuration.setOutputDirectory(previousOutputDirectory);
    }
  }

  /**
   * Write the captured versions using Allure's standard environment-properties format.
   *
   * @param allureResultsDirectory Allure results directory
   */
  void writeToAllure(Path allureResultsDirectory) {
    var properties = readAllureEnvironment(allureResultsDirectory);
    removeDeploymentVersionProperties(properties);
    properties.putAll(reportProperties(ALLURE_PROPERTY_PREFIX));
    writeProperties(
        allureResultsDirectory.resolve(ALLURE_ENVIRONMENT_FILE),
        properties,
        "Generated by the ZETA testsuite");
  }

  /**
   * Remove deployment versions from a previously generated Allure environment file.
   *
   * @param allureResultsDirectory Allure results directory
   */
  static void clearAllureEnvironment(Path allureResultsDirectory) {
    var environmentFile = allureResultsDirectory.resolve(ALLURE_ENVIRONMENT_FILE);
    if (!Files.isRegularFile(environmentFile)) {
      return;
    }
    var properties = readAllureEnvironment(allureResultsDirectory);
    removeDeploymentVersionProperties(properties);
    if (properties.isEmpty()) {
      try {
        Files.deleteIfExists(environmentFile);
      } catch (IOException exception) {
        throw new UncheckedIOException("Unable to remove the Allure environment file", exception);
      }
      return;
    }
    writeProperties(
        environmentFile, properties, "Generated by the ZETA testsuite");
  }

  /**
   * Load the existing Allure environment without discarding unrelated metadata.
   *
   * @param allureResultsDirectory Allure results directory
   * @return existing environment properties
   */
  private static Properties readAllureEnvironment(Path allureResultsDirectory) {
    return readProperties(allureResultsDirectory.resolve(ALLURE_ENVIRONMENT_FILE));
  }

  /**
   * Read a Java properties file when it exists.
   *
   * @param input properties file
   * @return loaded properties, or an empty set when the file does not exist
   */
  private static Properties readProperties(Path input) {
    var properties = new Properties();
    if (!Files.isRegularFile(input)) {
      return properties;
    }
    try (var reader = Files.newBufferedReader(input, StandardCharsets.UTF_8)) {
      properties.load(reader);
      return properties;
    } catch (IOException exception) {
      throw new UncheckedIOException("Unable to read report metadata from " + input, exception);
    }
  }

  /**
   * Remove only properties owned by deployment-version collection.
   *
   * @param properties mutable Allure environment properties
   */
  private static void removeDeploymentVersionProperties(Properties properties) {
    properties.keySet().removeIf(
        key -> key.toString().startsWith(ALLURE_PROPERTY_PREFIX));
  }

  /**
   * Persist report metadata using the standard Java properties format.
   *
   * @param output output file
   * @param properties report properties
   * @param comment generated-file comment
   */
  private static void writeProperties(
      Path output, Properties properties, String comment) {
    try {
      Files.createDirectories(output.getParent());
      try (var writer = Files.newBufferedWriter(
          output, StandardCharsets.UTF_8)) {
        properties.store(writer, comment);
      }
    } catch (IOException exception) {
      throw new UncheckedIOException("Unable to write report metadata to " + output, exception);
    }
  }

  /**
   * Create indexed report properties with the requested prefix.
   *
   * @param prefix property prefix
   * @return indexed version properties
   */
  private Properties reportProperties(String prefix) {
    var properties = new Properties();
    for (var index = 0; index < versions.size(); index++) {
      properties.setProperty(
          prefix + String.format("%04d", index + 1),
          versions.get(index).reportValue());
    }
    return properties;
  }

  /** A single Kubernetes pod container and its resolved image. */
  private record DeploymentVersion(String namespace, String pod, String container,
                                   String containerType, String image, String imageId) {

    /**
     * Create the exact pod section name shown by Serenity.
     *
     * @return namespace and pod
     */
    private String podName() {
      return "%s/%s".formatted(namespace, pod);
    }

    /**
     * Create the exact container row label shown by Serenity.
     *
     * @return container and container type
     */
    private String containerName() {
      return "%s [%s]".formatted(container, containerType);
    }

    /**
     * Create the image tag and digest shown by Serenity.
     *
     * @return normalized tag and digest separated by a vertical bar
     */
    private String reportImage() {
      var imageReference = withoutRegistry(image);
      if (imageId.isBlank()) {
        return imageReference;
      }
      var imageDigest = withoutRegistry(imageId);
      return imageReference.equals(imageDigest)
          ? imageReference
          : imageReference + " | " + imageDigest;
    }

    /**
     * Remove the transport scheme and registry host from an image reference.
     *
     * @param imageReference image tag or digest returned by Kubernetes
     * @return repository-relative image reference
     */
    private static String withoutRegistry(String imageReference) {
      var normalized = imageReference;
      var schemeSeparator = normalized.indexOf("://");
      if (schemeSeparator >= 0) {
        normalized = normalized.substring(schemeSeparator + 3);
      }
      var repositorySeparator = normalized.indexOf('/');
      return repositorySeparator >= 0
          ? normalized.substring(repositorySeparator + 1)
          : normalized;
    }

    /**
     * Render the value displayed in Serenity's environment details.
     *
     * @return human-readable container and image information
     */
    private String reportValue() {
      var resolvedImage = imageId.isBlank() ? image : image + " (" + imageId + ")";
      return namespace + "/" + pod + "/" + container + " [" + containerType + "] = "
          + resolvedImage;
    }
  }
}
