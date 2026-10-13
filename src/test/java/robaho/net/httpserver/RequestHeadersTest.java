package robaho.net.httpserver;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;
import org.testng.annotations.Test;

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
        assertEquals(r.headers().getFirst("KEY"),"VAL2");
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
        String request = "GET blah\r\nKEY : VAL\r\nKEY2:VAL2 \r\n\r\nSome Body Data";
        var is = new ByteArrayInputStream(request.getBytes());
        var os = new ByteArrayOutputStream();

        Request r = new Request(is,os);
        assertTrue("GET blah".contentEquals(r.requestLine()));
        assertEquals(r.headers().getFirst("KEY"),"VAL");
        assertEquals(r.headers().getFirst("KEY2"),"VAL2");
    }

    @Test
    public void TestDuplicateHeaders() throws IOException {
        String request = "GET blah\r\nKEY : VAL\r\nKEY:VAL2\r\nKEY:VAL3 \r\n\r\nSome Body Data";
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
    public void TestObsFoldTabAndNoSeparator() throws IOException {
        // a tab-led line folds into the value; no separator is inserted
        String request = "GET blah\r\nKEY:VAL\r\n\tEXT\r\n\r\n";
        var r = new Request(new ByteArrayInputStream(request.getBytes()), new ByteArrayOutputStream());
        assertEquals("VALEXT", r.headers().getFirst("KEY"));
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
    public void TestNoColonHeaderHasEmptyValue() throws IOException {
        String request = "GET blah\r\nKEYNOOLON\r\n\r\n";
        var r = new Request(new ByteArrayInputStream(request.getBytes()), new ByteArrayOutputStream());
        assertEquals("", r.headers().getFirst("KEYNOOLON"));
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