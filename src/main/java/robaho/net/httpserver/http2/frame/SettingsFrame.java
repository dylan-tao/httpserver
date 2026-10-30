package robaho.net.httpserver.http2.frame;

import robaho.net.httpserver.http2.Http2ErrorCode;
import robaho.net.httpserver.http2.Http2Exception;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;

public class SettingsFrame extends BaseFrame {

  ArrayList<SettingParameter> params = new ArrayList<>();

  public SettingsFrame(FrameHeader header) {
    super(header);
  }

  public ArrayList<SettingParameter> getSettingParameters() { return params; }

  public static SettingsFrame parse(byte[] frameBody, FrameHeader header) throws Http2Exception {
    if (header.streamIdentifier() != 0) {
      throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);
    }

    if (header.flags().contains(FrameFlag.ACK)) {
      if (header.length() != 0) {
        throw new Http2Exception(Http2ErrorCode.FRAME_SIZE_ERROR);
      }
    }

    if (frameBody.length % 6 != 0) {
      throw new Http2Exception(Http2ErrorCode.FRAME_SIZE_ERROR);
    }

    SettingsFrame result = new SettingsFrame(header);
    int paramIndex = 0;

    while (paramIndex < frameBody.length) {
      var param = SettingParameter.parse(Arrays.copyOfRange(frameBody, paramIndex, paramIndex + 6));
      if (param != null) {
        result.params.add(param);
      }
      paramIndex += 6;
    }
    return result;
  }

  @Override
  public void writeTo(OutputStream os) throws IOException {

    int settingBodySize = params.size() * SettingParameter.PARAMETER_SIZE;

    FrameHeader header =
        new FrameHeader(settingBodySize, FrameType.SETTINGS, getHeader().flags(), getHeader().streamIdentifier());
    header.writeTo(os);

    for (int i = 0; i < params.size(); i++) {
      params.get(i).writeTo(os);
    }
  }

}
