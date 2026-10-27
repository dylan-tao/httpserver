package robaho.net.httpserver;

import org.testng.annotations.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

public class RequestHeadersTest {
    @Test
    public void TestHeaderParse() throws IOException {
        String request = "GET blah\r\nKEY:VALUE\r\n\r\n";
        var is = new ByteArrayInputStream(request.getBytes());
        var os = new ByteArrayOutputStream();

        Request r = new Request(is,os);
        assertTrue("GET blah".contentEquals(r.requestLine()));
        assertEquals(r.headers().getFirst("KEY"),"VALUE");
    }
    @Test
    public void TestMultiLineHeaderParse() throws IOException {
        String request = "GET blah\r\nKEY:VAL\r\n 2\r\n\r\n";
        var is = new ByteArrayInputStream(request.getBytes());
        var os = new ByteArrayOutputStream();

        Request r = new Request(is,os);
        assertTrue("GET blah".contentEquals(r.requestLine()));
        // RFC 9112 5.2: obs-fold is replaced by a single SP
        assertEquals(r.headers().getFirst("KEY"),"VAL 2");
    }
    @Test
    public void TestMultipleHeaders() throws IOException {
        String request = "GET blah\r\nKEY:VAL\r\nKEY2:VAL2\r\n\r\n";
        var is = new ByteArrayInputStream(request.getBytes());
        var os = new ByteArrayOutputStream();

        Request r = new Request(is,os);
        assertTrue("GET blah".contentEquals(r.requestLine()));
        assertEquals(r.headers().getFirst("KEY"),"VAL");
        assertEquals(r.headers().getFirst("KEY2"),"VAL2");
    }
    @Test
    public void TestMultipleHeadersThenBody() throws IOException {
        String request = "GET blah\r\nKEY:VAL\r\nKEY2:VAL2\r\n\r\nSome Body Data";
        var is = new ByteArrayInputStream(request.getBytes());
        var os = new ByteArrayOutputStream();

        Request r = new Request(is,os);
        assertTrue("GET blah".contentEquals(r.requestLine()));
        assertEquals(r.headers().getFirst("KEY"),"VAL");
        assertEquals(r.headers().getFirst("KEY2"),"VAL2");
    }
    @Test
    public void TestWhitespace() throws IOException {
        // RFC 9112 5.1: whitespace between the field name and colon MUST be
        // rejected with 400 (a request-smuggling differential against
        // RFC-compliant proxies; the JDK 400s it). the old lenient trim
        // ("KEY : VAL" accepted) was removed
        String request = "GET blah\r\nKEY : VAL\r\nKEY2:VAL2 \r\n\r\nSome Body Data";
        var is = new ByteArrayInputStream(request.getBytes());
        var os = new ByteArrayOutputStream();

        var r = new Request(is,os);
        assertTrue("GET blah".contentEquals(r.requestLine()));
        assertThrows(IOException.class, () -> r.headers());
        // the legal whitespace cases still work: trailing space in the VALUE
        String ok = "GET blah\r\nKEY2:VAL2 \r\n\r\n";
        var r2 = new Request(new ByteArrayInputStream(ok.getBytes()), new ByteArrayOutputStream());
        assertEquals("VAL2", r2.headers().getFirst("KEY2"));
    }

    @Test
    public void TestDuplicateHeaders() throws IOException {
        String request = "GET blah\r\nKEY:VAL\r\nKEY:VAL2\r\nKEY:VAL3 \r\n\r\nSome Body Data";
        var is = new ByteArrayInputStream(request.getBytes());
        var os = new ByteArrayOutputStream();

        Request r = new Request(is,os);
        assertTrue("GET blah".contentEquals(r.requestLine()));
        assertEquals(r.headers().get("KEY"), List.of("VAL", "VAL2", "VAL3"));
    }

    // ---- bulk line reader rewrite: pin the preserved CR quirks and edges ----

    @Test
    public void TestBareLfLines() throws IOException {
        // bare LF terminates both the request line and headers (RFC 9112 2.2)
        String request = "GET blah\nKEY:VAL\n\n";
        var r = new Request(new ByteArrayInputStream(request.getBytes()), new ByteArrayOutputStream());
        assertEquals("GET blah", r.requestLine());
        assertEquals("VAL", r.headers().getFirst("KEY"));
    }

    @Test
    public void TestRequestLineTrailingCrQuirk() throws IOException {
        // a trailing CR run of exactly 1 is the CRLF terminator...
        var r1 = new Request(new ByteArrayInputStream("GET a\r\n\r\n".getBytes()), new ByteArrayOutputStream());
        assertEquals("GET a", r1.requestLine());
        // ... a run of 2+ is all CONTENT (the LF then terminated the line bare)
        var r2 = new Request(new ByteArrayInputStream("GET a\r\r\n\r\n".getBytes()), new ByteArrayOutputStream());
        assertEquals("GET a\r\r", r2.requestLine());
    }

    @Test
    public void TestHeaderTrailingCrSwallowed() throws IOException {
        // ALL trailing CRs before the LF are swallowed in header lines
        String request = "GET blah\r\nKEY:VAL\r\r\r\n\r\n";
        var r = new Request(new ByteArrayInputStream(request.getBytes()), new ByteArrayOutputStream());
        assertEquals("VAL", r.headers().getFirst("KEY"));
    }

    @Test
    public void TestHeaderBareCrRejected() throws IOException {
        // a CR followed by a non-CR/non-LF byte inside a header line is an error
        String request = "GET blah\r\nKE\rY:VAL\r\n\r\n";
        var r = new Request(new ByteArrayInputStream(request.getBytes()), new ByteArrayOutputStream());
        assertEquals("GET blah", r.requestLine());
        assertThrows(IOException.class, () -> r.headers());
    }

    @Test
    public void TestObsFoldTabInsertsSpace() throws IOException {
        // a tab-led line folds into the value; the obs-fold is replaced by
        // a single SP (RFC 9112 5.2)
        String request = "GET blah\r\nKEY:VAL\r\n\tEXT\r\n\r\n";
        var r = new Request(new ByteArrayInputStream(request.getBytes()), new ByteArrayOutputStream());
        assertEquals("VAL EXT", r.headers().getFirst("KEY"));
    }

    @Test
    public void TestWhitespaceOnlyLineIsNoOpFold() throws IOException {
        // a whitespace-only line is an obs-fold that appends nothing: the
        // header block continues after it
        String request = "GET blah\r\nKEY:VAL\r\n \r\nOTHER:V2\r\n\r\n";
        var r = new Request(new ByteArrayInputStream(request.getBytes()), new ByteArrayOutputStream());
        assertEquals("VAL", r.headers().getFirst("KEY"));
        assertEquals("V2", r.headers().getFirst("OTHER"));
    }

    @Test
    public void TestNoColonHeaderRejected() throws IOException {
        // a header line without a colon is a malformed field-line
        // (RFC 9112 5) and is rejected, matching the JDK
        String request = "GET blah\r\nKEYNOOLON\r\n\r\n";
        var r = new Request(new ByteArrayInputStream(request.getBytes()), new ByteArrayOutputStream());
        assertThrows(IOException.class, () -> r.headers());
    }

    @Test
    public void TestIllegalHeaderNameRejected() throws IOException {
        // a space inside the field-name is not an RFC 7230 tchar
        String request = "GET blah\r\nFoo Bar: 1\r\n\r\n";
        var r = new Request(new ByteArrayInputStream(request.getBytes()), new ByteArrayOutputStream());
        assertThrows(IOException.class, () -> r.headers());
    }

    @Test
    public void TestHeaderBlockBudgetExceeded() throws IOException {
        // the total header block budget (default 380KB) bounds the worst
        // case that the per-line and count limits alone allow (~25MB)
        byte[] bigValue = new byte[60 * 1024];
        java.util.Arrays.fill(bigValue, (byte) 'v');
        StringBuilder sb = new StringBuilder("GET blah\r\n");
        for (int i = 0; i < 8; i++) { // 8 x ~60KB = ~480KB > 380KB
            sb.append("X-Big-").append(i).append(':').append(new String(bigValue)).append("\r\n");
        }
        sb.append("\r\n");
        var r = new Request(new ByteArrayInputStream(sb.toString().getBytes()), new ByteArrayOutputStream());
        assertEquals("GET blah", r.requestLine());
        assertThrows(IOException.class, () -> r.headers());
    }

    @Test
    public void TestEofDropsPendingHeader() throws IOException {
        // headers without the terminating blank line are dropped at EOF
        String request = "GET blah\r\nKEY:VAL\r\n";
        var r = new Request(new ByteArrayInputStream(request.getBytes()), new ByteArrayOutputStream());
        assertEquals("GET blah", r.requestLine());
        assertNull(r.headers().getFirst("KEY"));
    }

    @Test
    public void TestLeadingEmptyLinesSkipped() throws IOException {
        String request = "\r\n\r\nGET blah\r\nKEY:VAL\r\n\r\n";
        var r = new Request(new ByteArrayInputStream(request.getBytes()), new ByteArrayOutputStream());
        assertEquals("GET blah", r.requestLine());
        assertEquals("VAL", r.headers().getFirst("KEY"));
    }

    @Test
    public void TestHeaderLineTooLong() throws IOException {
        // the per-builder limit: a value pushing past maxReqLineSize throws
        byte[] longValue = new byte[70 * 1024];
        java.util.Arrays.fill(longValue, (byte) 'v');
        String request = "GET blah\r\nKEY:" + new String(longValue) + "\r\n\r\n";
        var r = new Request(new ByteArrayInputStream(request.getBytes()), new ByteArrayOutputStream());
        assertEquals("GET blah", r.requestLine());
        assertThrows(IOException.class, () -> r.headers());
    }
}
