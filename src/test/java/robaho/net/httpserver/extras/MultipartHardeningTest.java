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
 * regressions for the 2026-10-23 audit multipart findings: the OOM caps,
 * same-filename clobbering, and silent truncation
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
    String full = "--b\r\n" + field("a", "first") + "\r\n--b\r\n" + field("b", "seco"); // cut mid-data, no closing boundary
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
   * a filename containing ';' inside quotes must be extracted intact
   * (the plain split(";") cut it mid-value)
   */
  @Test
  public void testQuotedSemicolonFilename() throws IOException {
    byte[] body = multipart("b", file("f", "a;b.txt", "X"));
    var result = MultipartFormParser.parse("UTF-8", "multipart/form-data; boundary=b", new ByteArrayInputStream(body), storage);
    Assert.assertEquals(result.get("f").get(0).filename(), "a;b.txt");
  }

  /**
   * the boundary parameter is case-insensitive and must not match
   * xboundary=
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

  /**
   * an aborted parse must not leave its already-created files on disk (audit
   * E4): a body that creates a file part, then hits the truncation, throws -
   * and the file must be deleted on the way out, not pile up until the JVM
   * exits (repeated abuse piled files until disk exhaustion). red before the
   * fix: the file survived with only the shutdown hook as a backstop
   */
  @Test
  public void testAbortedParseDeletesCreatedFiles() throws IOException {
    // a file part whose data is cut off mid-stream: no closing boundary
    byte[] body = ("--b\r\n" + "Content-Disposition: form-data; name=\"f\"; filename=\"aborted.txt\"\r\n"
        + "Content-Type: text/plain\r\n\r\n" + "partial-content-without-closing-boundary").getBytes(StandardCharsets.UTF_8);
    try {
      MultipartFormParser.parse("UTF-8", "multipart/form-data; boundary=b", new ByteArrayInputStream(body), storage);
      Assert.fail("the truncated body must be rejected");
    } catch (IOException expected) {
      // truncated multipart body - but the file was already created
    }
    try (var left = Files.list(storage)) {
      var leftovers = left.toList();
      Assert.assertTrue(leftovers.isEmpty(), "an aborted parse must delete its already-created files: " + leftovers);
    }
  }

  /**
   * the total cap covers EVERY byte of the body, not just part data (audit
   * E4): a boundary-less preamble larger than max_form_bytes must trip the
   * total cap. red before the fix: the preamble streamed uncounted, and only
   * the EOF at the end rejected the body (a different error) - leaving the
   * door open to unbounded streaming under the old data-only accounting
   */
  @Test
  public void testPreambleCountsTowardTotalCap() {
    // 10MB + 1 of preamble garbage, no boundary anywhere
    byte[] preamble = new byte[10 * 1024 * 1024 + 1];
    java.util.Arrays.fill(preamble, (byte) 'x');
    try {
      MultipartFormParser.parse("UTF-8", "multipart/form-data; boundary=never-found", new ByteArrayInputStream(preamble),
          storage);
      Assert.fail("a preamble above the total cap must be rejected");
    } catch (IOException e) {
      Assert.assertTrue(e.getMessage().contains("exceeds the configured maximum"),
          "the total-bytes cap must trip during the preamble, not the EOF at the end: " + e.getMessage());
    }
  }

}
