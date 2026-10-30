package robaho.net.httpserver.extras;

import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * regressions for the 2026-10-23 audit multipart findings: the OOM caps, same-filename clobbering, and silent
 * truncation
 */
public class MultipartHardeningTest {

  private Path storage;

  @BeforeMethod
  void setUp() throws java.io.IOException {
    storage = Files.createTempDirectory("multipart-test");
  }

  @AfterMethod
  void tearDown() throws java.io.IOException {
    try (var walk = Files.walk(storage)) {
      for (var p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
        java.nio.file.Files.delete(p);
      }
    }
  }

  private static byte[] multipart(String boundary, String... parts) {
    StringBuilder b = new StringBuilder();
    for (String p : parts) {
      b.append("--").append(boundary).append("\r\n").append(p).append("\r\n");
    }
    b.append("--").append(boundary).append("--\r\n");
    return b.toString().getBytes(StandardCharsets.UTF_8);
  }

  private static String field(String name, String value) {
    return "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value;
  }

  private static String file(String name, String filename, String value) {
    return "Content-Disposition: form-data; name=\"" + name + "\"; filename=\"" + filename + "\"\r\n"
        + "Content-Type: text/plain\r\n\r\n" + value;
  }

  /** two file parts with the same filename must not overwrite each other */
  @Test
  public void testDuplicateFilenamesDoNotClobber() throws IOException {
    byte[] body = multipart("b", file("f1", "a.txt", "FIRST"), file("f2", "a.txt", "SECOND"));
    var result = MultipartFormParser.parse("UTF-8", "multipart/form-data; boundary=b", new ByteArrayInputStream(body), storage);
    List<MultipartFormParser.Part> f1 = result.get("f1");
    List<MultipartFormParser.Part> f2 = result.get("f2");
    Assert.assertEquals(new String(Files.readAllBytes(f1.get(0).file().toPath())), "FIRST");
    Assert.assertEquals(new String(Files.readAllBytes(f2.get(0).file().toPath())), "SECOND");
    Assert.assertNotEquals(f1.get(0).file(), f2.get(0).file(), "same-named parts need distinct files");
  }

  /** a body cut off mid-part-data must throw, not return the partial map */
  @Test
  public void testTruncatedBodyThrows() {
    String full = "--b\r\n" + field("a", "first") + "\r\n--b\r\n" + field("b", "seco"); // cut mid-data, no closing
                                                                                        // boundary
    try {
      MultipartFormParser.parse("UTF-8", "multipart/form-data; boundary=b",
          new ByteArrayInputStream(full.getBytes(StandardCharsets.UTF_8)), storage);
      Assert.fail("a truncated multipart body must throw");
    } catch (IOException expected) {
      Assert.assertTrue(expected.getMessage().contains("truncated"),
          "message should name the truncation: " + expected.getMessage());
    }
  }

  /**
   * a filename containing ';' inside quotes must be extracted intact (the plain split(";") cut it mid-value)
   */
  @Test
  public void testQuotedSemicolonFilename() throws IOException {
    byte[] body = multipart("b", file("f", "a;b.txt", "X"));
    var result = MultipartFormParser.parse("UTF-8", "multipart/form-data; boundary=b", new ByteArrayInputStream(body), storage);
    Assert.assertEquals(result.get("f").get(0).filename(), "a;b.txt");
  }

  /**
   * the boundary parameter is case-insensitive and must not match xboundary=
   */
  @Test
  public void testBoundaryCaseInsensitiveAndWordBound() throws IOException {
    byte[] body = multipart("REAL", field("k", "v"));
    var result = MultipartFormParser.parse("UTF-8", "multipart/form-data; xboundary=WRONG; BOUNDARY=REAL",
        new ByteArrayInputStream(body), storage);
    Assert.assertEquals(result.get("k").get(0).data(), "v");
  }

  /** an endless part header line must be rejected, not OOM the JVM */
  @Test
  public void testEndlessPartHeaderLineRejected() {
    StringBuilder head = new StringBuilder("--b\r\nX-Huge: ");
    head.repeat('a', 200 * 1024); // 200KB single header line, no CRLF
    head.append("\r\n\r\nv\r\n--b--\r\n");
    try {
      MultipartFormParser.parse("UTF-8", "multipart/form-data; boundary=b",
          new ByteArrayInputStream(head.toString().getBytes(StandardCharsets.UTF_8)), storage);
      Assert.fail("an oversized part header line must be rejected");
    } catch (IOException expected) {
      // capped: either the line limit or the truncation at EOF
    }
  }

}
