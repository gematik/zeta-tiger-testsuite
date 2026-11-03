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

package de.gematik.zeta.services;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Edits source spans; comments, quoted arguments and variables never delimit a block. */
final class NginxRouteConfiguration {
  private static final Pattern TOKENS = Pattern.compile(
      "#[^\\r\\n]*+|\\s++|[{};]|(?:\"(?:\\\\.|[^\"\\\\])*+\"|'(?:\\\\.|[^'\\\\])*+'|\\\\.|"
          + "\\$\\{[^}]*+}|\\$(?!\\{)|[^\\s{};\"'\\\\$]++)++", Pattern.DOTALL);
  private static final List<String> ROUTING = List.of("rewrite", "try_files", "error_page", "return");

  private NginxRouteConfiguration() {
  }

  static String patch(String config, String route, String regex, String directive,
      String genericPrefix, String upstreamBasePath) {
    var parts = scan(config);
    var location = location(parts, route);
    if (location == null) {
      var generic = route.startsWith(genericPrefix) && !route.equals(genericPrefix) ? location(parts, genericPrefix) : null;
      if (generic == null || generic.modifier().equals("=")) {
        throw new AssertionError("No generic nginx prefix location for " + route);
      }
      var passes = children(parts, generic).stream().filter(part -> part.name().equals("proxy_pass")).toList();
      if (passes.size() != 1 || passes.getFirst().words.size() != 2) {
        throw new AssertionError("Cannot derive nginx upstream for " + route);
      }
      var pass = passes.getFirst();
      var base = pass.words.get(1).replaceFirst("/$", "");
      if (!base.endsWith(upstreamBasePath) || base.contains("$")) {
        throw new AssertionError("Unsupported generic nginx upstream for " + route);
      }
      var body = config.substring(generic.body, pass.start)
          + "proxy_pass " + base + "/" + route.substring(genericPrefix.length()) + ";"
          + config.substring(pass.end, generic.end - 1);
      var derived = patch("location = " + route + " {" + body + "}\n", route, regex, directive, genericPrefix, upstreamBasePath);
      return config.substring(0, generic.start) + derived + config.substring(generic.start);
    }
    var expected = expectedDirective(directive);
    var matches = directives(parts, location, regex, expected.name());
    if (matches.size() > 1) {
      throw new AssertionError("Conflicting nginx directives for " + route);
    }
    var current = matches.isEmpty() ? null : matches.getFirst();
    var patched = current == null
        ? config.substring(0, location.end - 1) + "\n    " + directive + "\n" + config.substring(location.end - 1)
        : current.words.equals(expected.words) ? config
            : config.substring(0, current.start) + directive + config.substring(current.end);
    requireDirective(patched, route, regex, directive);
    return patched;
  }

  static String restrictPrefixToExact(String config, String prefix) {
    var parts = scan(config);
    var target = location(parts, prefix);
    if (target == null) {
      throw new AssertionError("Missing nginx prefix location: " + prefix);
    }
    if (target.modifier().equals("=")) {
      var generic = location(parts.stream().filter(part -> part != target).toList(), prefix);
      return generic == null ? config : config.substring(0, generic.start) + config.substring(generic.end);
    }
    return config.substring(0, target.start) + "location = " + prefix + " {"
        + config.substring(target.body);
  }

  static void requireExactDumpLocation(String dump, String prefix) {
    var parts = scan(expandedDump(dump));
    var target = location(parts, prefix);
    if (target == null || !target.modifier().equals("=")
        || literalLocations(parts, prefix).stream().anyMatch(part -> !part.modifier().equals("="))) {
      throw new AssertionError("Nginx location must match exactly: " + prefix);
    }
  }

  static void requireDumpDirective(String dump, String route, String regex, String directive) {
    requireDirective(expandedDump(dump), route, regex, directive);
  }

  private static String expandedDump(String dump) {
    var files = new LinkedHashMap<String, String>();
    var markers = Pattern.compile("(?m)^# configuration file ([^\\r\\n]+):\\s*$").matcher(dump);
    String previous = null;
    int start = 0;
    while (markers.find()) {
      if (previous != null) {
        files.put(previous, dump.substring(start, markers.start()));
      }
      previous = markers.group(1);
      start = markers.end();
    }
    if (previous == null) {
      throw new AssertionError("nginx -T returned no configuration files");
    }
    files.put(previous, dump.substring(start));
    var main = files.keySet().iterator().next();
    return expand(main, main.substring(0, main.lastIndexOf('/') + 1), files, new HashSet<>());
  }

  private static String expand(String file, String prefix, Map<String, String> files, Set<String> active) {
    if (!active.add(file)) {
      throw new AssertionError("Recursive nginx include: " + file);
    }
    var text = files.get(file);
    if (text == null) {
      throw new AssertionError("nginx -T is missing included file " + file);
    }
    var result = new StringBuilder(text);
    for (var include : scan(text).stream().filter(part -> part.name().equals("include"))
        .sorted(Comparator.comparingInt((Part part) -> part.start).reversed()).toList()) {
      var path = include.words.get(1);
      result.replace(include.start, include.end, expand(path.startsWith("/") ? path : prefix + path, prefix, files, active));
    }
    active.remove(file);
    return result.toString();
  }

  private static void requireDirective(String config, String route, String regex, String directive) {
    var parts = scan(config);
    var location = location(parts, route);
    var expected = expectedDirective(directive);
    var actual = location == null ? List.<Part>of() : directives(parts, location, regex, expected.name());
    if (actual.size() != 1 || !actual.getFirst().words.equals(expected.words)) {
      throw new AssertionError("Requested nginx directive is not effective for " + route);
    }
  }

  private static Part expectedDirective(String directive) {
    var parts = scan(directive);
    if (parts.size() != 1 || parts.getFirst().body >= 0) {
      throw new IllegalArgumentException("Expected exactly one nginx directive");
    }
    return parts.getFirst();
  }

  private static List<Part> directives(List<Part> parts, Part location, String regex, String name) {
    var pattern = Pattern.compile(regex);
    return children(parts, location).stream().filter(part -> part.name().equals(name)
        || pattern.matcher(String.join(" ", part.words) + ";").matches()).toList();
  }

  private static List<Part> literalLocations(List<Part> parts, String route) {
    return parts.stream().filter(part -> part.name().equals("location")
        && List.of("", "=", "^~").contains(part.modifier()) && part.words.getLast().equals(route)).toList();
  }

  private static Part location(List<Part> parts, String route) {
    var matches = literalLocations(parts, route);
    if (matches.isEmpty()) {
      return null;
    }
    if (matches.stream().map(NginxRouteConfiguration::server).distinct().count() > 1) {
      throw new AssertionError("Ambiguous nginx server for " + route);
    }
    var exact = matches.stream().filter(part -> part.modifier().equals("=")).toList();
    matches = exact.isEmpty() ? matches : exact;
    if (matches.size() != 1) {
      throw new AssertionError("Ambiguous nginx location for " + route);
    }
    var location = matches.getFirst();
    var context = server(location);
    if (location.body < 0 || location.parent != context || children(parts, location).stream().anyMatch(part -> part.body >= 0 && !headerStripGuard(parts, part))) {
      throw new AssertionError("Unsupported nested nginx block for " + route);
    }
    if (parts.stream().filter(part -> part.parent == location || part.parent == context).anyMatch(part ->
        ROUTING.contains(part.name()) || part.name().equals("if") && !denialGuard(parts, part) && !headerStripGuard(parts, part))) {
      throw new AssertionError("Unsupported nginx routing for " + route);
    }
    if (location.modifier().isEmpty()) {
      for (var alternative : children(parts, context)) {
        if (alternative.name().equals("location") && List.of("~", "~*").contains(alternative.modifier())) {
          var regex = alternative.words.getLast();
          var prefix = Pattern.compile("\\^(/[A-Za-z0-9_-]+/)(?![?*+{])").matcher(regex);
          var literal = prefix.lookingAt() && !hasUnboundedAlternation(regex) ? prefix.group(1) : "";
          var target = route;
          if (alternative.modifier().equals("~*")) {
            literal = literal.toLowerCase(Locale.ROOT);
            target = target.toLowerCase(Locale.ROOT);
          }
          if (literal.isEmpty() || target.startsWith(literal) || literal.startsWith(target)) {
            throw new AssertionError("Unsupported nginx regex routing for " + route + ": " + regex);
          }
        }
      }
    }
    return location;
  }

  /**
   * Rejects unscoped alternatives and syntax whose alternative scope is not resolved.
   *
   * @param regex nginx location expression
   * @return whether alternatives can bypass the initial literal prefix
   */
  private static boolean hasUnboundedAlternation(String regex) {
    if (!regex.contains("|")) {
      return false;
    }
    if (!regex.matches("[A-Za-z0-9_/^$().|:-]+")) {
      return true;
    }
    var depth = 0;
    for (var character : regex.toCharArray()) {
      if (character == '(') {
        depth++;
      } else if (character == ')' && --depth < 0 || character == '|' && depth == 0) {
        return true;
      }
    }
    return depth != 0;
  }

  /**
   * Recognizes conditional removal of consumed client credentials from proxy headers.
   *
   * @param parts parsed nginx directives
   * @param guard conditional block
   * @return whether the block only clears the matching credential header variable
   */
  private static boolean headerStripGuard(List<Part> parts, Part guard) {
    if (!guard.name().equals("if") || guard.words.size() != 2) {
      return false;
    }
    var condition = Pattern.compile("\\(\\$zeta_strip_(authorization|dpop|popp)\\)")
        .matcher(guard.words.get(1));
    var actions = children(parts, guard);
    return condition.matches() && actions.size() == 1 && actions.getFirst().body < 0
        && actions.getFirst().words.equals(List.of("set", "$zeta_" + condition.group(1), ""));
  }

  private static boolean denialGuard(List<Part> parts, Part guard) {
    var actions = children(parts, guard);
    return actions.size() == 1 && actions.getFirst().body < 0 && actions.getFirst().words.size() == 2
        && actions.getFirst().name().equals("return") && actions.getFirst().words.get(1).matches("4[0-9]{2}");
  }

  private static Part server(Part part) {
    while (part.parent != null && !part.name().equals("server")) {
      part = part.parent;
    }
    return part;
  }

  private static List<Part> children(List<Part> parts, Part parent) {
    return parts.stream().filter(part -> part.parent == parent).toList();
  }

  private static List<Part> scan(String text) {
    var parts = new ArrayList<Part>();
    var root = new Part(null, 0);
    var parent = root;
    Part part = null;
    var tokens = TOKENS.matcher(text);
    int end = 0;
    while (tokens.find()) {
      if (tokens.start() != end) {
        throw new AssertionError("Invalid nginx syntax");
      }
      end = tokens.end();
      var token = tokens.group();
      if (token.isBlank() || token.startsWith("#")) {
        continue;
      }
      if (token.equals("}")) {
        if (part != null || parent == root) {
          throw new AssertionError("Unexpected nginx closing brace");
        }
        parent.end = end;
        parent = parent.parent;
      } else {
        if (part == null) {
          part = new Part(parent, tokens.start());
        }
        if (token.equals(";") || token.equals("{")) {
          if (part.words.isEmpty()) {
            throw new AssertionError("Missing nginx directive name");
          }
          if (part.name().equals("include") && (token.equals("{") || part.words.size() != 2
              || !part.words.get(1).matches("/?[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*")
              || List.of(part.words.get(1).split("/")).contains(".."))) {
            throw new AssertionError("Unsupported nginx include");
          }
          parts.add(part);
          part.end = end;
          if (token.equals("{")) {
            part.body = end;
            parent = part;
          }
          part = null;
        } else {
          part.words.add(argument(token));
        }
      }
    }
    if (end != text.length() || part != null || parent != root) {
      throw new AssertionError("Unterminated nginx configuration");
    }
    return parts;
  }

  private static String argument(String token) {
    var value = new StringBuilder();
    char quote = 0;
    for (int i = 0; i < token.length(); i++) {
      char character = token.charAt(i);
      if (character == '\\') {
        char escaped = token.charAt(++i);
        switch (escaped) {
          case 'n' -> value.append('\n');
          case 'r' -> value.append('\r');
          case 't' -> value.append('\t');
          case '\\', '"', '\'' -> value.append(escaped);
          default -> value.append('\\').append(escaped);
        }
      } else if (character == quote) {
        quote = 0;
      } else if (quote == 0 && (character == '"' || character == '\'')) {
        quote = character;
      } else {
        value.append(character);
      }
    }
    return value.toString();
  }

  private static final class Part {
    private final Part parent;
    private final int start;
    private final List<String> words = new ArrayList<>();
    private int body = -1;
    private int end;

    private Part(Part parent, int start) {
      this.parent = parent;
      this.start = start;
    }

    private String name() {
      return words.isEmpty() ? "" : words.getFirst();
    }

    private String modifier() {
      return words.size() == 3 ? words.get(1) : "";
    }
  }
}
