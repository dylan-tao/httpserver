package robaho.net.httpserver.http2.frame;

import robaho.net.httpserver.http2.HTTP2ErrorCode;
import robaho.net.httpserver.http2.HTTP2Exception;
import robaho.net.httpserver.http2.Utils;

import java.io.IOException;
import java.io.OutputStream;

public class SettingParameter {

	static final int PARAMETER_SIZE = 6;

	public SettingIdentifier identifier;
	public long value;

    /** RFC 7540 6.9.1: the initial flow-control window size, both stream and
     * connection level, unless changed by SETTINGS or WINDOW_UPDATE */
    public static final long INITIAL_WINDOW_SIZE = 65535;

    public static SettingParameter DEFAULT_INITIAL_WINDOWSIZE = new SettingParameter(SettingIdentifier.SETTINGS_INITIAL_WINDOW_SIZE, (int) INITIAL_WINDOW_SIZE);

    public SettingParameter() {
    }

    public SettingParameter(SettingIdentifier identifier, long value) {
        this.identifier = identifier;
        this.value = value;
    }

	public static SettingParameter parse(byte[] param) throws HTTP2Exception
	{
        try {
            if(param.length != 6) {
                throw new HTTP2Exception(HTTP2ErrorCode.FRAME_SIZE_ERROR);
            }
            SettingParameter result = new SettingParameter();
            result.identifier = SettingIdentifier.getEnum(Utils.convertToInt(param,0, 2));
            if(result.identifier == SettingIdentifier.SETTINGS_NONE) {
                return null;
            }
            result.value = Utils.convertToLong(param, 2, 4);
            if(!result.identifier.validateValue(result.value)) {
                throw new HTTP2Exception(HTTP2ErrorCode.PROTOCOL_ERROR,"Invalid value for setting "+result.identifier+" "+result.value);
            }
            return result;
        } catch (HTTP2Exception ex) {
            throw ex;
        } catch (Exception ex) {
            throw new HTTP2Exception(HTTP2ErrorCode.PROTOCOL_ERROR,ex);
        }
	}
    public void writeTo(OutputStream os) throws IOException {
        Utils.writeBinary(os,identifier.getValue(), 2);
        Utils.writeBinary(os,(int)value, 4);
    }
}
