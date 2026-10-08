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

import de.gematik.test.tiger.lib.reports.SerenityReportUtils;
import io.cucumber.java.Scenario;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import lombok.extern.slf4j.Slf4j;
import net.serenitybdd.core.Serenity;

/**
 * Shared reporting bridge for data that should be visible in Serenity and Allure.
 */
@Slf4j
public final class ReportAttachments {

  private static final long MAX_ALLURE_FILE_ATTACHMENT_BYTES = 25L * 1024L * 1024L;
  private static final ThreadLocal<Scenario> CURRENT_SCENARIO = new ThreadLocal<>();

  /**
   * Prevents instantiation of the static reporting helper.
   */
  private ReportAttachments() {
  }

  /**
   * Registers the active Cucumber scenario for Allure-compatible attachments.
   *
   * @param scenario active scenario
   */
  public static void setCurrentScenario(final Scenario scenario) {
    CURRENT_SCENARIO.set(scenario);
  }

  /**
   * Clears the active Cucumber scenario after the scenario lifecycle is complete.
   */
  public static void clearCurrentScenario() {
    CURRENT_SCENARIO.remove();
  }

  /**
   * Adds a text block to Serenity and, when a Cucumber scenario is active, as an Allure text attachment.
   *
   * @param title attachment title
   * @param contents attachment contents
   */
  public static void addText(final String title, final String contents) {
    var safeContents = contents == null ? "" : contents;
    SerenityReportUtils.addCustomData(title, safeContents);
    attachToAllure(title, "text/plain", safeContents.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Adds a preformatted text block as an HTML attachment so Allure renders it inline.
   *
   * @param title attachment title
   * @param contents plain-text contents wrapped in a {@code <pre>} block
   */
  public static void addHtml(final String title, final String contents) {
    var safeContents = contents == null ? "" : contents;
    SerenityReportUtils.addCustomData(title, safeContents);
    var escaped = safeContents
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;");
    var html = "<pre style=\"font-family:monospace;font-size:13px;white-space:pre-wrap\">"
        + escaped + "</pre>";
    attachToAllure(title, "text/html", html.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Adds an existing file as a downloadable Serenity report artifact and to Allure.
   *
   * @param title attachment title
   * @param file file to attach
   * @param mediaType MIME type for the Allure attachment
   */
  public static void addFile(final String title, final Path file, final String mediaType) {
    if (file == null || !Files.exists(file)) {
      addText(title, "Attachment file not found: " + file);
      return;
    }

    try {
      var normalizedFile = file.toAbsolutePath().normalize();
      long size = Files.size(normalizedFile);
      Serenity.recordReportData().withTitle(title).downloadable().fromFile(normalizedFile);
      if (size > MAX_ALLURE_FILE_ATTACHMENT_BYTES) {
        addText(
            title + " (not attached to Allure)",
            "File is larger than "
                + MAX_ALLURE_FILE_ATTACHMENT_BYTES
                + " bytes and was left on disk: "
                + normalizedFile);
        return;
      }
      attachToAllure(title, mediaType, Files.readAllBytes(normalizedFile));
    } catch (IOException e) {
      addText(title, "Could not attach file '" + file + "': " + e.getMessage());
    }
  }

  /**
   * Adds one attachment to the current Cucumber scenario, which the Allure Cucumber plugin picks up.
   *
   * @param title attachment title
   * @param mediaType MIME type
   * @param data attachment bytes
   */
  private static void attachToAllure(final String title, final String mediaType, final byte[] data) {
    var scenario = CURRENT_SCENARIO.get();
    if (scenario == null) {
      log.debug("Skipping Allure attachment '{}' because no current Cucumber scenario is registered", title);
      return;
    }

    try {
      scenario.attach(data, mediaType, title);
    } catch (RuntimeException e) {
      log.warn("Could not attach '{}' to Allure", title, e);
    }
  }
}
