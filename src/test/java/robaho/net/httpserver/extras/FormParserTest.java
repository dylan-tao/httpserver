package robaho.net.httpserver.extras;

import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** guards the FormParser rewrite (index scanning instead of split): the parse
 *  results must stay identical, including the split() quirks */
public class FormParserTest {

    @Test
    public void testBasic() throws IOException {
        Map<String, List<String>> params = FormParser.parse("UTF-8",
            new ByteArrayInputStream("a=1&b=two".getBytes(StandardCharsets.UTF_8)));
        Assert.assertEquals(params.size(), 2);
        Assert.assertEquals(params.get("a"), List.of("1"));
        Assert.assertEquals(params.get("b"), List.of("two"));
    }

    /** keys are decoded BEFORE grouping: "a=1" and "%61=2" merge (servlet semantics) */
    @Test
    public void testKeyDecodedBeforeGrouping() throws IOException {
        Map<String, List<String>> params = FormParser.parse("UTF-8",
            new ByteArrayInputStream("a=1&%61=2".getBytes(StandardCharsets.UTF_8)));
        Assert.assertEquals(params.size(), 1);
        Assert.assertEquals(params.get("a"), List.of("1", "2"));
    }

    /** the _charset_ field declares the encoding of the other values */
    @Test
    public void testCharsetParam() throws IOException {
        Map<String, List<String>> params = FormParser.parse("ISO-8859-1",
            new ByteArrayInputStream("_charset_=UTF-8&k=%E4%B8%AD".getBytes(StandardCharsets.UTF_8)));
        Assert.assertEquals(params.get("k"), List.of("中"));
    }

    /** malformed percent encoding is a client error, not an uncaught IAE */
    @Test(expectedExceptions = IOException.class)
    public void testInvalidPercentEncoding() throws IOException {
        FormParser.parse("UTF-8", new ByteArrayInputStream("a=%zz".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void testEmptyBody() throws IOException {
        Map<String, List<String>> params = FormParser.parse("UTF-8", new ByteArrayInputStream(new byte[0]));
        Assert.assertTrue(params.isEmpty());
    }

    /** a key without '=' carries no value and is absent from the decoded
     *  result (the original split-based parser dropped it in the decode phase
     *  as well: its value list is empty); the trailing '&' must NOT create an
     *  empty-key entry (split() drops trailing empty segments) */
    @Test
    public void testValuelessKeyAndTrailingAmpersand() throws IOException {
        Map<String, List<String>> params = FormParser.parse("UTF-8",
            new ByteArrayInputStream("flag&x=1&".getBytes(StandardCharsets.UTF_8)));
        Assert.assertEquals(params.size(), 1);
        Assert.assertNull(params.get("flag"), "valueless keys are absent from the result");
        Assert.assertEquals(params.get("x"), List.of("1"));
    }

    /** '=' inside the value stays in the value (split("=", 2) semantics) */
    @Test
    public void testEncodedEqualsInValue() throws IOException {
        Map<String, List<String>> params = FormParser.parse("UTF-8",
            new ByteArrayInputStream("expr=a%3Db".getBytes(StandardCharsets.UTF_8)));
        Assert.assertEquals(params.get("expr"), List.of("a=b"));
    }
}
