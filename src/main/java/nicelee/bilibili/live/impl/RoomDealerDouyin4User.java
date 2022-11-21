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

	final static Pattern pWebcastId = Pattern.compile("https://webcast.amemv.com/(?:douyin/)?webcast/reflow/([0-9]+)");

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
			if (webcastId == null) {
				System.err.println("getLiveUrl: No webcastId");
				return null;
			}

			String flvUrl = getFlvUrlFromWebcastId(webcastId);
			Logger.println(flvUrl);

			return flvUrl;
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
		HashMap<String, String> header = new HashMap<>();
		header.put("User-Agent", userAgent);
		Logger.println("Filename=" + fileName);
		util.download(url, fileName + ".flv", header);
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
			System.err.println("handleReflow: shortId currently not supported");
			return null;
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
			System.err.println("handleReflow: shortId URL currently not supported");
			return null;
		}

		System.err.println("handeReflow: Unexpected location: " + location);
		return null;
	}

	private JSONObject getRoomDataFromWebcastId(String webcastId) throws InterruptedException {
		Logger.println("getRoomDataFromWebcastId: " + webcastId);
		String reflowUrl = "https://webcast.amemv.com/webcast/room/reflow/info/?type_id=0&live_id=1&room_id=" + webcastId + "&app_id=1128";
		reflowUrl += "&verifyFp=verify_lanqy33f_duFQpbWD_Anj2_42Ej_B899_MYXW9QN2vMGl&sec_user_id=MS4wLjABAAAAs9J_QWC-OjjRmbXiHrs7rt-AVuaGqWcx3w64y14nHtk&msToken=&X-Bogus=";
		JSONObject json = fetchJson(reflowUrl);
		if(json == null) {
			System.err.println("getRoomDataFromWebcastId: Failed to fetch JSON");
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
						System.err.println("getRoomDataFromWebcastId: Found new stream with id " + ownRoomId);
						return getRoomDataFromWebcastId(ownRoomId);
					}
				}
			}

			System.err.println("getRoomDataFromWebcastId: Stream has finished");
			return null;
		}

		return json;
	}

	private RoomInfo getRoomInfoFromWebcastId(String webcastId) throws InterruptedException {
		JSONObject json = getRoomDataFromWebcastId(webcastId);

		JSONObject room = json.getJSONObject("data").getJSONObject("room");
		JSONObject anchor = room.getJSONObject("owner");

		RoomInfo roomInfo = new RoomInfo();
		roomInfo.setRemark(webcastId);
		String shortId = anchor.getString("web_rid");
		roomInfo.setShortId(shortId);
		roomInfo.setRoomId(shortId);
		roomInfo.setUserName(anchor.getString("nickname"));
		roomInfo.setUserId(anchor.getLong("id"));
		roomInfo.setTitle(room.getString("title"));
		roomInfo.setDescription(anchor.getString("nickname") + " 的直播间");

		JSONObject stream_url = room.optJSONObject("stream_url");

		if (stream_url == null) {
			System.err.println("getRoomInfoFromWebcastId: Not live. Status: " + room.getInt("status"));
			roomInfo.setLiveStatus(0);
		} else {
			processQualities(roomInfo, stream_url);
			roomInfo.setLiveStatus(1);
		}

		roomInfo.print();
		logRoom(room);
		return roomInfo;
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

	private String getFlvUrlFromWebcastId(String webcastId) throws InterruptedException {
		JSONObject json = getRoomDataFromWebcastId(webcastId);

		JSONObject room = json.getJSONObject("data").getJSONObject("room");
		JSONObject stream_url = room.optJSONObject("stream_url");

		if (stream_url == null) {
			System.err.println("getFlvUrlFromWebcastId: Not live. Status: " + room.getInt("status"));
			return null;
		}

		return getFlvUrl(stream_url);
	}

	private String getFlvUrl(JSONObject stream_url) {
		JSONObject urls = stream_url.getJSONObject("flv_pull_url");
		if (urls.has("ORIGION")) {
			Logger.println("Selected quality: Origin");

			return urls.getString("ORIGION");
		} else if (urls.has("FULL_HD1")) {
			Logger.println("Selected quality: UHD");

			return urls.getString("FULL_HD1");
		} else if (urls.has("HD1")) {
			Logger.println("Selected quality: HD");

			return urls.getString("HD1");
		} else if (urls.has("SD2")) {
			Logger.println("Selected quality: SD");

			return urls.getString("SD2");
		} else if (urls.has("SD1")) {
			Logger.println("Selected quality: LD");

			return urls.getString("SD1");
		}

		return null;
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
