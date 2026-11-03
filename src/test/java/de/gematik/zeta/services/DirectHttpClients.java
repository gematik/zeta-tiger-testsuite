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

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.Socket;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import org.bouncycastle.jsse.util.CustomSSLSocketFactory;
import org.bouncycastle.util.IPAddress;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

/** Creates management clients that bypass Tiger's JVM-wide proxy settings. */
public final class DirectHttpClients {

  static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

  /** Prevents instantiation of this factory. */
  private DirectHttpClients() {
  }

  /**
   * Creates a direct JDK client builder, retaining caller-specific TLS and protocol configuration.
   *
   * @return builder for an independent HTTP client
   */
  public static HttpClient.Builder httpClientBuilder() {
    return HttpClient.newBuilder()
        .proxy(ProxySelector.of(null))
        .connectTimeout(CONNECT_TIMEOUT);
  }

  /**
   * Creates a direct Spring request factory with adjustable timeouts for deadline-based polling.
   *
   * @param readTimeout response timeout
   * @return request factory for a RestTemplate
   */
  public static SimpleClientHttpRequestFactory requestFactory(Duration readTimeout) {
    var factory = new SimpleClientHttpRequestFactory();
    factory.setProxy(Proxy.NO_PROXY);
    factory.setConnectTimeout(CONNECT_TIMEOUT);
    factory.setReadTimeout(readTimeout);
    return factory;
  }

  /**
   * Creates a direct client for the PoPP test backend, which can use untrusted test certificates.
   *
   * <p>This preserves the permissive TLS behavior of the former Tiger forwarding connection.
   * Trust and hostname overrides apply only to this factory's connections; do not use it for
   * certificate-conformance assertions or change JVM-wide TLS defaults.</p>
   *
   * @param readTimeout response timeout
   * @return direct request factory with connection-local test TLS settings
   */
  public static SimpleClientHttpRequestFactory testBackendRequestFactory(Duration readTimeout) {
    try {
      return testBackendRequestFactory(readTimeout, SslConfigurationService.getTrustAllSslContext());
    } catch (Exception e) {
      throw new IllegalStateException("Could not configure TLS for the direct test backend client.", e);
    }
  }

  /**
   * Creates a direct backend client using an explicitly supplied TLS context.
   *
   * @param readTimeout response timeout
   * @param sslContext connection-local TLS context
   * @return direct request factory retaining the backend's SNI and hostname handling
   */
  public static SimpleClientHttpRequestFactory testBackendRequestFactory(Duration readTimeout, SSLContext sslContext) {
    final var socketFactory = sslContext.getSocketFactory();
    var factory = new SimpleClientHttpRequestFactory() {
      /**
       * Applies test TLS settings to this backend connection only.
       *
       * @param connection backend connection
       * @param httpMethod requested method
       * @throws IOException if connection preparation fails
       */
      @Override
      protected void prepareConnection(HttpURLConnection connection, String httpMethod) throws IOException {
        super.prepareConnection(connection, httpMethod);
        if (connection instanceof HttpsURLConnection httpsConnection) {
          var host = httpsConnection.getURL().getHost();
          httpsConnection.setSSLSocketFactory(IPAddress.isValid(host.replace("[", "").replace("]", ""))
              ? socketFactory : new BackendSniSocketFactory(socketFactory, host));
          httpsConnection.setHostnameVerifier((hostname, session) -> true);
        }
      }
    };
    factory.setProxy(Proxy.NO_PROXY);
    factory.setConnectTimeout(CONNECT_TIMEOUT);
    factory.setReadTimeout(readTimeout);
    return factory;
  }

  /**
   * Supplies URL-derived SNI even when HttpsURLConnection creates an unconnected BC socket.
   * Its later IP-only connect otherwise loses the original destination hostname.
   */
  private static final class BackendSniSocketFactory extends CustomSSLSocketFactory {

    private final SNIHostName serverName;

    /**
     * Binds SNI to one backend URL without modifying the shared SSL context.
     *
     * @param delegate configured TLS socket factory
     * @param hostname original URL hostname
     */
    private BackendSniSocketFactory(SSLSocketFactory delegate, String hostname) {
      super(delegate);
      this.serverName = new SNIHostName(hostname);
    }

    /**
     * Sets explicit SNI before any handshake without changing other TLS parameters.
     *
     * @param socket newly created TLS socket
     * @return configured socket
     */
    @Override
    protected Socket configureSocket(Socket socket) {
      var tlsSocket = (SSLSocket) socket;
      var parameters = tlsSocket.getSSLParameters();
      parameters.setServerNames(List.of(serverName));
      tlsSocket.setSSLParameters(parameters);
      return socket;
    }
  }
}
