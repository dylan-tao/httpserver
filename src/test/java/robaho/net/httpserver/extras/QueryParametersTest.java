package robaho.net.httpserver.extras;

import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.UnsupportedEncodingException;

/**
 * QueryParameters had ZERO tests despite being exported API: key
 * percent-decoding-before-grouping and the malformed-escape wrap
 */
public class QueryParametersTest {

  @Test
  public void testPercentDecodedKeysGroup() throws UnsupportedEncodingException {
    var qp = QueryParameters.decode("UTF-8", "%61=1&a=2");
    Assert.assertEquals(qp.get("a"), java.util.List.of("1", "2"), "decoded keys must group with their plain form");
  }

  @Test
  public void testValuelessAndMulti() throws UnsupportedEncodingException {
    var qp = QueryParameters.decode("UTF-8", "flag&x=1&x=2");
    Assert.assertEquals(qp.getFirst("flag"), "");
    Assert.assertEquals(qp.get("x"), java.util.List.of("1", "2"));
  }

  @Test
  public void testMalformedEscapeWrappedAsDeclared() {
    // URLDecoder throws UNCHECKED IAE; the API declares
    // UnsupportedEncodingException - the wrapper must convert
    Assert.assertThrows(UnsupportedEncodingException.class, () -> QueryParameters.decode("UTF-8", "a=%zz"));
  }

}
