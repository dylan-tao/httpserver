package robaho.net.httpserver.extras;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * library-managed file responses. on plain h1 the body reaches the socket
 * via the kernel sendfile path (FileChannel.transferTo) - the handler
 * thread never copies the bytes; on h2 or TLS the file is streamed through
 * the normal response path.
 * <pre>
 * Sendfile.serve(exchange, path, "application/octet-stream");
 * </pre>
 */
public final class Sendfile {

  /**
   * the exchange attribute claiming the library-managed file body. set it
   * (or call {@link #serve}) before {@code sendResponseHeaders}; the value
   * must be a {@link Path} and the declared content length must equal the
   * file size. the library clears the attribute when it claims the body
   */
  public static final String ATTRIBUTE = "robaho.net.httpserver.sendFile";

  private Sendfile() {}

  /**
   * responds with a file body: 200, the given content type, and the file
   * bytes as a fixed-length body.
   * @param ex the exchange
   * @param file the file to send; its size becomes the content length
   * @param contentType the Content-Type to set, or null to leave unset
   */
  public static void serve(HttpExchange ex, Path file, String contentType) throws IOException {
    long len = Files.size(file);
    if (contentType != null) {
      ex.getResponseHeaders().set("Content-Type", contentType);
    }
    if ("HEAD".equals(ex.getRequestMethod())) {
      // per the HttpExchange contract a HEAD response must set
      // Content-Length manually: sendResponseHeaders ignores the
      // length for HEAD (no body path, the attribute is never claimed)
      ex.getResponseHeaders().set("Content-Length", Long.toString(len));
      ex.sendResponseHeaders(200, -1);
      try (OutputStream os = ex.getResponseBody()) {
        os.flush();
      }
      return;
    }
    ex.setAttribute(ATTRIBUTE, file);
    ex.sendResponseHeaders(200, len);
    try (OutputStream os = ex.getResponseBody()) {
      if (ex.getAttribute(ATTRIBUTE) != null) {
        // the core did not claim the attribute (an h2 exchange): stream
        // the file through the normal response path here
        try (InputStream in = Files.newInputStream(file)) {
          in.transferTo(os);
        }
      }
    }
  }

}
