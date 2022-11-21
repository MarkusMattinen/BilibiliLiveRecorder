package nicelee.bilibili.live.impl;

import java.io.UnsupportedEncodingException;
import java.io.IOException;
import java.lang.InterruptedException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONObject;

import nicelee.bilibili.live.RoomDealer;
import nicelee.bilibili.live.domain.RoomInfo;
import nicelee.bilibili.util.HttpCookies;
import nicelee.bilibili.util.Logger;

public class RoomDealerDouyin4User extends RoomDealer {

	final public static String liver = "douyin";
	final static String userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/97.0.4692.99 Safari/537.36";

	final static Pattern pJson = Pattern.compile("<script id=\"RENDER_DATA\".*>(.*?)</script></head>");
	final static Pattern pShortId = Pattern.compile("live.douyin.com/([0-9]+)");
	final static Pattern pWebcastId = Pattern.compile("https://webcast.amemv.com/(?:douyin/)?webcast/reflow/([0-9]+)");

	final static Pattern pJsonMobile = Pattern.compile("<script>window.__INIT_PROPS__ *= *(.*?)</script>");
	@Override
	public String getType() {
		return ".flv";
	}

	/**
	 * @param shortUrl
	 * @return
	 */
	@Override
	public RoomInfo getRoomInfo(String shortUrl) {
		try {
			return handleReflow(shortUrl);
		} catch (Exception e) {
			e.printStackTrace();
			System.err.println("getRoomInfo: 抖音需要cookie, 请确认cookie是否存在或失效");
			return null;
		}
	}

	@Override
	public String getLiveUrl(String roomId, String qn, Object... obj) {
		try {
			String webcastId = (String) obj[0];
			JSONObject stream_url = null;

			if(webcastId != null) {
				Logger.println("请求仅能在移动端播放的链接");
				String urlStr = "https://webcast.amemv.com/webcast/reflow/" + webcastId;
				Logger.println("Get: " + urlStr);
				String html = util.getContent(urlStr, getMobileHeader());

				String jsonStr = tryMatch(html, pJsonMobile);
				Logger.println(jsonStr);

				JSONObject json = new JSONObject(jsonStr);
				JSONObject room = json.getJSONObject("/webcast/reflow/:id").getJSONObject("room");
				stream_url = room.getJSONObject("stream_url");
			}else {
				Logger.println("请求PC Web端播放的链接");
				String urlStr = "https://live.douyin.com/" + roomId;
				Logger.println("Get: " + urlStr);
				String html = util.getContent(urlStr, getPCHeader(), HttpCookies.convertCookies(cookie));

				String encodedJson = tryMatch(html, pJson);
				String jsonStr = URLDecoder.decode(encodedJson, "UTF-8");
				Logger.println(jsonStr);

				JSONObject json = new JSONObject(jsonStr);
				JSONObject room = json.getJSONObject("initialState").getJSONObject("roomStore").getJSONObject("roomInfo").getJSONObject("room");
				stream_url = room.optJSONObject("stream_url");
			}

			if (stream_url == null) {
				System.err.println("getLiveUrl: No stream URL");
				return null;
			}

			JSONArray flv_sources = stream_url.getJSONObject("live_core_sdk_data").getJSONObject("pull_data").getJSONObject("options").getJSONArray("qualities");
			int flv_sources_len = flv_sources.length();
			String sdk_key = null;
			for (int i = 0; i < flv_sources_len; i++) {
				JSONObject quality = flv_sources.getJSONObject(i);
				int level = quality.getInt("level");
				// 这里要与前面一致
				if (qn.equals("" + (flv_sources_len - level))) {
					sdk_key = quality.getString("sdk_key");
					break;
				}
			}

			String pull_data = stream_url.getJSONObject("live_core_sdk_data").getJSONObject("pull_data").getString("stream_data");
			JSONObject data = new JSONObject(pull_data).getJSONObject("data");
			String link = data.getJSONObject(sdk_key).getJSONObject("main").getString("flv");
			Logger.println(link);

			return link;
		} catch (Exception e) {
			e.printStackTrace();
			return null;
		}
	}

	/**
	 * 开始录制
	 *
	 * @param url
	 * @param fileName
	 * @param shortId
	 * @return
	 */
	@Override
	public void startRecord(String url, String fileName, String shortId) {
		HashMap<String, String> mobile = new HashMap<>();
		mobile.put("User-Agent", "Mozilla/5.0 (Android 9.0; Mobile; rv:68.0) Gecko/68.0 Firefox/68.0");
		Logger.println("Filename=" + fileName);
		util.download(url, fileName + ".flv", mobile);
	}

	private HashMap<String, String> mobileHeader;
	private HashMap<String, String> pcHeader;
	private HashMap<String, String> getPCHeader(){
		if(pcHeader == null) {
			pcHeader = new HashMap<>();
			pcHeader.put("User-Agent", userAgent);
			pcHeader.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
			pcHeader.put("Accept-Language", "zh-CN,zh;q=0.8,zh-TW;q=0.7,zh-HK;q=0.5,en-US;q=0.3,en;q=0.2");
		}
		return pcHeader;
	}
	private HashMap<String, String> getMobileHeader(){
		if(mobileHeader == null) {
			mobileHeader = new HashMap<>();
			mobileHeader.put("User-Agent", "Mozilla/5.0 (Android 9.0; Mobile; rv:68.0) Gecko/68.0 Firefox/68.0");
			mobileHeader.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
			mobileHeader.put("Accept-Language", "zh-CN,zh;q=0.8,zh-TW;q=0.7,zh-HK;q=0.5,en-US;q=0.3,en;q=0.2");
		}
		return mobileHeader;
	}

	private RoomInfo handleReflow(String location) throws InterruptedException, UnsupportedEncodingException, MalformedURLException, IOException {
		if (location == null) {
			System.err.println("handleReflow: location is null");
			return null;
		}

		Logger.println("handleReflow: " + location);

		if (!location.startsWith("http")) {
			return getRoomInfoFromShortId(location);
		}

		if (location.startsWith("https://v.douyin.com")) {
			String nextLocation = fetchNextLocation(location);
			return handleReflow(nextLocation);
		}

		if (location.startsWith("https://www.iesdouyin.com")) {
			String nextLocation = fetchNextLocation(location);
			return handleReflow(nextLocation);
		}

		if (location.startsWith("https://webcast.amemv.com")) {
			String webcastId = tryMatch(location, pWebcastId);
			if (webcastId == null) {
				System.err.println("handleReflow: Could not parse webcastId!");
				return null;
			}

			return getRoomInfoFromWebcastId(webcastId);
		}

		if (location.startsWith("https://live.douyin.com/")) {
			String shortId = tryMatch(location, pShortId);
			if (shortId == null) {
				System.err.println("handleReflow: Could not parse shortId!");
				return null;
			}

			return getRoomInfoFromShortId(shortId);
		}

		System.err.println("handeReflow: Unexpected location: " + location);
		return null;
	}

	private RoomInfo getRoomInfoFromWebcastId(String webcastId) throws InterruptedException {
		Logger.println("getRoomInfoFromWebcastId: " + webcastId);
		String reflowUrl = "https://webcast.amemv.com/webcast/room/reflow/info/?type_id=0&live_id=1&room_id=" + webcastId + "&app_id=1128";
		reflowUrl += "&verifyFp=verify_lanqy33f_duFQpbWD_Anj2_42Ej_B899_MYXW9QN2vMGl&sec_user_id=MS4wLjABAAAAs9J_QWC-OjjRmbXiHrs7rt-AVuaGqWcx3w64y14nHtk&msToken=&X-Bogus=";
		JSONObject json = fetchJson(reflowUrl);
		if(json == null) {
			System.err.println("getRoomInfo: Failed to fetch JSON");
			return null;
		}

		JSONObject room = json.getJSONObject("data").getJSONObject("room");
		JSONObject anchor = room.getJSONObject("owner");

		if(room.getInt("status") == 4) {
			JSONObject ownRoomData = anchor.optJSONObject("own_room");
			if (ownRoomData != null) {
				JSONArray ownRoomIds = ownRoomData.getJSONArray("room_ids_str");

				if (ownRoomIds.length() > 0) {
					String ownRoomId = ownRoomIds.getString(0);

					if (ownRoomId != webcastId) {
						System.err.println("getRoomInfo: Found new stream with id " + ownRoomId);
						return getRoomInfoFromWebcastId(ownRoomId);
					}
				}
			}

			System.err.println("getRoomInfo: Stream has finished");
			return null;
		}

		RoomInfo roomInfo = new RoomInfo();
		String shortId = anchor.getString("web_rid");
		roomInfo.setShortId(shortId);
		roomInfo.setRoomId(shortId);
		roomInfo.setUserName(anchor.getString("nickname"));
		roomInfo.setUserId(anchor.getLong("id"));
		roomInfo.setTitle(room.getString("title"));
		roomInfo.setDescription(anchor.getString("nickname") + " 的直播间");

		JSONObject stream_url = room.optJSONObject("stream_url");

		if (stream_url == null) {
			if(room.getInt("status") == 2) {
				webcastId = room.getString("id_str");
				processRoomInfoFromWebcastId(roomInfo, webcastId);
			} else {
				roomInfo.setLiveStatus(0);
			}
		} else {
			roomInfo.setLiveStatus(1);
			processQualities(roomInfo, stream_url);
		}

		roomInfo.print();
		logRoom(room);
		return roomInfo;
	}

	private RoomInfo getRoomInfoFromShortId(String shortId) throws UnsupportedEncodingException {
		Logger.println("getRoomInfoFromShortId: " + shortId);
		RoomInfo roomInfo = new RoomInfo();
		roomInfo.setShortId(shortId);
		roomInfo.setRoomId(shortId);

		String urlStr = "https://live.douyin.com/" + shortId;
		Logger.println("Get: " + urlStr);
		String html = util.getContent(urlStr, getPCHeader(), HttpCookies.convertCookies(cookie));
		// Logger.println(html);

		String encodedJson = tryMatch(html, pJson);
		String jsonStr = URLDecoder.decode(encodedJson, "UTF-8");
		Logger.println(jsonStr);

		JSONObject json = new JSONObject(jsonStr);
		JSONObject info = json.getJSONObject("initialState").getJSONObject("roomStore").getJSONObject("roomInfo");
		JSONObject room = info.getJSONObject("room");
		JSONObject anchor = info.getJSONObject("anchor");
		JSONObject stream_url = room.optJSONObject("stream_url");

		roomInfo.setUserName(anchor.getString("nickname"));
		roomInfo.setUserId(anchor.optLong("id_str"));
		roomInfo.setTitle(room.getString("title"));
		roomInfo.setDescription(anchor.getString("nickname") + " 的直播间");
		logRoom(room);

		if (stream_url == null) {
			if (room.getInt("status") == 2) {
				String webcastId = room.getString("id_str");
				processRoomInfoFromWebcastId(roomInfo, webcastId);
			} else {
				roomInfo.setLiveStatus(0);
			}
		} else {
			roomInfo.setLiveStatus(1);
			processQualities(roomInfo, stream_url);
		}

		roomInfo.print();
		return roomInfo;
	}

	private void processRoomInfoFromWebcastId(RoomInfo roomInfo, String webcastId) {
		Logger.println("processRoomInfoFromWebcastId: " + webcastId);
		roomInfo.setRemark(webcastId);
		roomInfo.setLiveStatus(1);

		String urlStr = "https://webcast.amemv.com/webcast/reflow/" + webcastId;
		Logger.println("Get: " + urlStr);
		String html = util.getContent(urlStr, getMobileHeader());

		String jsonStr = tryMatch(html, pJsonMobile);
		Logger.println(jsonStr);

		JSONObject json = new JSONObject(jsonStr);
		JSONObject room = json.getJSONObject("/webcast/reflow/:id").getJSONObject("room");
		JSONObject stream_url = room.getJSONObject("stream_url");

		processQualities(roomInfo, stream_url);
	}

	private String fetchNextLocation(String urlStr) throws MalformedURLException, IOException {
		Logger.println("fetchNextLocation: " + urlStr);
		URL url = new URL(urlStr);
		HttpURLConnection conn = (HttpURLConnection) url.openConnection();
		conn.setInstanceFollowRedirects(false);
		conn.setRequestProperty("User-Agent", userAgent);
		conn.connect();

		String location = conn.getHeaderField("Location");

		if (location == null) {
			Logger.println("Next location is null: " + conn.getResponseMessage() + " " + conn.getResponseCode());
			return null;
		}

		if (location.startsWith("/")) {
			URL locationUrl = new URL(url, location);
			location = locationUrl.toString();
		}

		Logger.println("Location: " + location);

		return location;
	}

	private JSONObject fetchJson(String urlStr) throws InterruptedException {
		Logger.println("fetchJson: " + urlStr);
		String jsonStr = "";
		int attempt = 0;
		while (attempt < 5) {
			Logger.println("Get: " + urlStr);
			jsonStr = util.getContent(urlStr, getPCHeader());

			if (jsonStr.length() > 10) {
				Logger.println(jsonStr);
				JSONObject json = new JSONObject(jsonStr);

				return json;
			}

			attempt++;
			Thread.sleep(1000);
		}

		return null;
	}

	private void processQualities(RoomInfo roomInfo, JSONObject stream_url) {
		JSONArray flv_sources = stream_url.getJSONObject("live_core_sdk_data").getJSONObject("pull_data").getJSONObject("options").getJSONArray("qualities");
		int flv_sources_len = flv_sources.length();
		String[] qn = new String[flv_sources_len];
		String[] qnDesc = new String[flv_sources_len];
		for (int i = 0; i < flv_sources_len; i++) {
			// 为了让0, 1, 2, 3 数字越小清晰度越高
			JSONObject obj = flv_sources.getJSONObject(i);
			int level = obj.getInt("level");
			qn[i] = "" + i;
			qnDesc[flv_sources_len - level] = obj.getString("name");
		}
		roomInfo.setAcceptQuality(qn);
		roomInfo.setAcceptQualityDesc(qnDesc);
	}

	private String tryMatch(String location, Pattern pattern) {
		Matcher matcher = pattern.matcher(location);
		if (matcher.find()) {
			return matcher.group(1);
		} else {
			return null;
		}
	}

	private void logRoom(JSONObject room) {
		JSONObject metadata = new JSONObject();
		JSONObject anchor = room.getJSONObject("owner");
		metadata.put("nickname", anchor.getString("nickname"));
		metadata.put("displayId", anchor.getString("display_id"));
		metadata.put("title", room.getString("title"));
		metadata.put("signature", anchor.getString("signature"));
		metadata.put("userCity", anchor.getString("city"));
		metadata.put("locationCity", anchor.getString("location_city"));
		Logger.println("Metadata=" + metadata.toString());
	}
}
