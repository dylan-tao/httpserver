package robaho.net.httpserver;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import java.net.InetSocketAddress;

class SSLConfigurator {

  static void configure(SSLSocket s, HttpsConfigurator cfg) {
    s.setUseClientMode(false);
    if (cfg == null)
      return;
    InetSocketAddress remoteAddress = (InetSocketAddress) s.getRemoteSocketAddress();
    Parameters params = new Parameters(cfg, remoteAddress);
    cfg.configure(params);
    SSLParameters sslParams = params.getSSLParameters();
    if (sslParams != null) {
      s.setSSLParameters(sslParams);
    } else {
      // tiger compatibility (mirrors sun.net.httpserver.SSLStreams):
      // a configurator that only uses the legacy per-item setters
      // (setCipherSuites/setProtocols/setNeedClientAuth/...) never
      // calls setSSLParameters; apply those instead of silently
      // dropping them
      String[] cipherSuites = params.getCipherSuites();
      if (cipherSuites != null) {
        try {
          s.setEnabledCipherSuites(cipherSuites);
        } catch (IllegalArgumentException e) { /* unsupported suite */ }
      }
      if (params.getNeedClientAuth()) {
        s.setNeedClientAuth(true);
      } else if (params.getWantClientAuth()) {
        s.setWantClientAuth(true);
      }
      String[] protocols = params.getProtocols();
      if (protocols != null) {
        try {
          s.setEnabledProtocols(protocols);
        } catch (IllegalArgumentException e) { /* unsupported protocol */ }
      }
    }
  }

  static class Parameters extends HttpsParameters {

    InetSocketAddress addr;

    HttpsConfigurator cfg;

    Parameters(HttpsConfigurator cfg, InetSocketAddress addr) {
      this.addr = addr;
      this.cfg = cfg;
    }

    @Override
    public InetSocketAddress getClientAddress() { return addr; }

    @Override
    public HttpsConfigurator getHttpsConfigurator() { return cfg; }

    SSLParameters params;

    @Override
    public void setSSLParameters(SSLParameters p) { params = p; }

    SSLParameters getSSLParameters() { return params; }

  }

}
