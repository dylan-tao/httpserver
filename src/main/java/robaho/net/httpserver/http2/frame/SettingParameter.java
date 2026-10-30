package robaho.net.httpserver.http2.frame;

import robaho.net.httpserver.http2.Http2ErrorCode;
import robaho.net.httpserver.http2.Http2Exception;
import robaho.net.httpserver.http2.internal.BinaryCodec;

import java.io.IOException;
import java.io.OutputStream;

public class SettingParameter {

	static final int PARAMETER_SIZE = 6;

	public SettingIdentifier identifier;
	public long value;

    /** RFC 7540 6.9.1: the initial flow-control window size, both stream and
     * connection level, unless changed by SETTINGS or WINDOW_UPDATE */
    public static final long INITIAL_WINDOW_SIZE = 65535;

    public static final SettingParameter DEFAULT_INITIAL_WINDOWSIZE = new SettingParameter(SettingIdentifier.SETTINGS_INITIAL_WINDOW_SIZE, (int) INITIAL_WINDOW_SIZE);

    public SettingParameter() {
    }

    public SettingParameter(SettingIdentifier identifier, long value) {
        this.identifier = identifier;
        this.value = value;
    }

	public static SettingParameter parse(byte[] param) throws Http2Exception
	{
        try {
            if(param.length != 6) {
                throw new Http2Exception(Http2ErrorCode.FRAME_SIZE_ERROR);
            }
            SettingParameter result = new SettingParameter();
            result.identifier = SettingIdentifier.getEnum(BinaryCodec.readInt(param,0, 2));
            if(result.identifier == SettingIdentifier.SETTINGS_NONE) {
                return null;
            }
            result.value = BinaryCodec.readLong(param, 2, 4);
            if(!result.identifier.validateValue(result.value)) {
                throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR,"Invalid value for setting "+result.identifier+" "+result.value);
            }
            return result;
        } catch (Http2Exception ex) {
            throw ex;
        } catch (Exception ex) {
            throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR,ex);
        }
	}
    public void writeTo(OutputStream os) throws IOException {
        BinaryCodec.writeInt(os,identifier.getValue(), 2);
        BinaryCodec.writeInt(os,(int)value, 4);
    }
}
