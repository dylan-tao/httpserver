package robaho.net.httpserver.http2.hpack;

import robaho.net.httpserver.http2.Http2ErrorCode;
import robaho.net.httpserver.http2.Http2Exception;
import robaho.net.httpserver.internal.BloomSet;
import robaho.net.httpserver.internal.OpenAddressMap;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * container for emitted Http2HeaderField from field block decoding. Many of the rules of valid
 * headers require multi-field inspection, so aggregating and validating at the end is an easier
 * design
 */
public class HeaderFieldList implements Iterable<Http2HeaderField> {
    private static final BloomSet prohibitedHeaderFields = BloomSet.of("connection");
    private static final BloomSet requiredHeaderFields = BloomSet.of(":path",":method",":scheme");
    private static final BloomSet pseudoHeadersIn = BloomSet.of(":authority", ":method", ":path", ":scheme");

    private final List<Http2HeaderField> fields = new ArrayList<>();
    private final OpenAddressMap pseudoHeaders = new OpenAddressMap(8);

    private boolean hasNonPseudoHeader = false;

    /**
     * add a Http2HeaderField to the collection performing any per field validation
     */
    public void addHeaderField(Http2HeaderField field) throws Http2Exception{
        if(field.isPseudoHeader() && !pseudoHeadersIn.contains(field.getName())) {
            throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, "invalid pseudo header " + field.getName());
        }
        if(prohibitedHeaderFields.contains(field.getName())) {
            throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR,"prohibited header field "+field.getName());
        }
        if(field.getName().equals("te") && !field.getValue().equals("trailers")) {
            throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR,"prohibited header field "+field.getName());
        }
        if(!field.isPseudoHeader()) {
            hasNonPseudoHeader = true;
        }
        if(field.isPseudoHeader() && hasNonPseudoHeader) {
            throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR,"pseudo-header fields must appear before regular header fields");
        }
        if(field.isPseudoHeader()) {
            if(pseudoHeaders.put(field.getName(),field)!=null && requiredHeaderFields.contains(field.getName())) {
                throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR,"invalid duplicate header "+field.getName());
            }
        }
        fields.add(field);
    }
    private static boolean isEmpty(String s) {
        return s == null || s.trim().equals("");
    }
    public void addAll(List<Http2HeaderField> httpFields) throws Http2Exception {
        for(Http2HeaderField field : httpFields) {
            addHeaderField(field);
        }
    }
    @Override
    public Iterator<Http2HeaderField> iterator() {
        return fields.iterator();
    }
    /**
     * perform the multi-field validation of the collection of header fields
     */
    public void validate() throws Http2Exception {
        for(var fieldName : requiredHeaderFields.values()) {
            var ph = pseudoHeaders.get(fieldName);
            if(ph==null || isEmpty(((Http2HeaderField)ph).getValue())) {
                throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR,"missing required header field "+fieldName);
            }
        }
    }
    public int size() {
        return fields.size();
    }
}
