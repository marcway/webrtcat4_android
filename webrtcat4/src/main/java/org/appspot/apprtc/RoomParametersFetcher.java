/*
 *  Copyright 2014 The WebRTC Project Authors. All rights reserved.
 *
 *  Use of this source code is governed by a BSD-style license
 *  that can be found in the LICENSE file in the root of the source
 *  tree. An additional intellectual property rights grant can be found
 *  in the file PATENTS.  All contributing project authors may
 *  be found in the AUTHORS file in the root of the source tree.
 */

package org.appspot.apprtc;

import android.util.Log;

import org.appspot.apprtc.AppRTCClient.SignalingParameters;
import org.appspot.apprtc.util.AsyncHttpURLConnection;
import org.appspot.apprtc.util.AsyncHttpURLConnection.AsyncHttpEvents;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.IceCandidate;
import org.webrtc.PeerConnection;
import org.webrtc.SessionDescription;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Scanner;

/**
 * AsyncTask that converts an AppRTC room URL into the set of signaling
 * parameters to use with that room.
 */
public class RoomParametersFetcher {
  private static final String TAG = "RoomRTCClient";
  private static final int TURN_HTTP_TIMEOUT_MS = 5000;
  private final RoomParametersFetcherEvents events;
  private final String roomUrl;
  private final String roomMessage;
  private AsyncHttpURLConnection httpConnection;

  /**
   * Room parameters fetcher callbacks.
   */
  public static interface RoomParametersFetcherEvents {
    /**
     * Callback fired once the room's signaling parameters
     * SignalingParameters are extracted.
     */
    public void onSignalingParametersReady(final SignalingParameters params);

    /**
     * Callback for room parameters extraction error.
     */
    public void onSignalingParametersError(final String description);
  }

  public RoomParametersFetcher(String roomUrl, String roomMessage,
      final RoomParametersFetcherEvents events) {
    this.roomUrl = roomUrl;
    this.roomMessage = roomMessage;
    this.events = events;
  }

  public void makeRequest() {
    Log.d(TAG, "Connecting to room: " + roomUrl);
    httpConnection = new AsyncHttpURLConnection(
        "POST", roomUrl, roomMessage,
        new AsyncHttpEvents() {
          @Override
          public void onHttpError(String errorMessage) {
            Log.e(TAG, "Room connection error: " + errorMessage);
            events.onSignalingParametersError(errorMessage);
          }

          @Override
          public void onHttpComplete(String response) {
            roomHttpResponseParse(response);
          }
        });
    httpConnection.send();
  }

  private void roomHttpResponseParse(String response) {
    Log.d(TAG, "Room response: " + response);
    try {
      LinkedList<IceCandidate> iceCandidates = null;
      SessionDescription offerSdp = null;
      JSONObject roomJson = new JSONObject(response);

      String result = roomJson.getString("result");
      if (!result.equals("SUCCESS")) {
        events.onSignalingParametersError("Room response error: " + result);
        return;
      }
      response = roomJson.getString("params");
      roomJson = new JSONObject(response);
      String roomId = roomJson.getString("room_id");
      String clientId = roomJson.getString("client_id");
      String wssUrl = roomJson.getString("wss_url");
      String wssPostUrl = roomJson.getString("wss_post_url");
      String turnTimeLimitedLTCURL = null;
      if (roomJson.has("turn_time_limited_ltc_url")) {
        turnTimeLimitedLTCURL = roomJson.getString("turn_time_limited_ltc_url");
      }
      boolean initiator = (roomJson.getBoolean("is_initiator"));
      if (!initiator) {
        iceCandidates = new LinkedList<IceCandidate>();
        String messagesString = roomJson.getString("messages");
        JSONArray messages = new JSONArray(messagesString);
        for (int i = 0; i < messages.length(); ++i) {
          String messageString = messages.getString(i);
          JSONObject message = new JSONObject(messageString);
          String messageType = message.getString("type");
          Log.d(TAG, "GAE->C #" + i + " : " + messageString);
          if (messageType.equals("offer")) {
            String messageSdp = message.getString("sdp");
            String[] parts = messageSdp.split("\n");
            Integer index = 0;

            StringBuilder sb = new StringBuilder();

            for(String frase: parts){
              String newFrase = frase;
              if (frase.contains("ufrag")){

                newFrase = frase.replace(" ", "+");
              }
              if (frase.contains("ice-pwd")){

                newFrase = frase.replace(" ", "+");
              }
              sb.append(newFrase);
              if (index != parts.length - 1){
                sb.append("\n");
              }
              index ++;
            }



            offerSdp = new SessionDescription(SessionDescription.Type.fromCanonicalForm(messageType),
                    sb.toString());
          } else if (messageType.equals("candidate")) {
            String messageSdp = message.getString("candidate");

            final int len = messageSdp.length();

            Boolean uFragFound = false;
            StringBuilder sb = new StringBuilder();
            Integer charactersUfrag = 0;

            for (int index = 0; index < len; index++) {
              Character character = messageSdp.charAt(index);
              if(!uFragFound){
                if(index > 3){

                  if(character == 'g'){

                    Character characterPrev = messageSdp.charAt(index - 1);
                    if(characterPrev == 'a') {
                      Character characterPrevPrev = messageSdp.charAt(index - 2);
                      if(characterPrevPrev == 'r') {
                        Character characterPrevPrevPrev = messageSdp.charAt(index - 3);
                        if(characterPrevPrevPrev == 'f') {
                          uFragFound = true;
                        }
                      }
                    }
                  }
                }
                sb.append(character);
              }
              else{
                if(charactersUfrag == 0){
                  sb.append(character);
                }
                else if (charactersUfrag > 0 && charactersUfrag < 5){
                  if(character == ' '){
                    sb.append("+");
                  }
                  else{
                    sb.append(character);
                  }
                }
                else{
                  sb.append(character);
                }

                charactersUfrag++;
              }

            }



            IceCandidate candidate = new IceCandidate(
                    message.getString("id"),
                    message.getInt("label"),
                    sb.toString());
            iceCandidates.add(candidate);
          } else {
            Log.e(TAG, "Unknown message: " + messageString);
          }
        }
      }
      Log.d(TAG, "RoomId: " + roomId + ". ClientId: " + clientId);
      Log.d(TAG, "Initiator: " + initiator);
      Log.d(TAG, "WSS url: " + wssUrl);
      Log.d(TAG, "WSS POST url: " + wssPostUrl);

      List<PeerConnection.IceServer> iceServers =
          iceServersFromPCConfigJSON(roomJson.getString("pc_config"));

      // If TURN servers do not have a username/password,
      // and a time-limited TURN url was provided, request credentials and update the iceServers list
      boolean sigParamsOK = true;
      if (!checkTURNServers(iceServers)) {
        if (turnTimeLimitedLTCURL != null) {
          Log.d(TAG, "Request TURN credentials from: " + turnTimeLimitedLTCURL);
          iceServers = getCredentialsForTURNServers(turnTimeLimitedLTCURL, iceServers);
        } else {
          sigParamsOK = false;
          events.onSignalingParametersError(
                  "No username nor password provided for TURN server(s)");
        }
      }

      /* LINO currently we always provide the list of TURN servers at room join time
      boolean isTurnPresent = false;
      for (PeerConnection.IceServer server : iceServers) {
        Log.d(TAG, "IceServer: " + server);
        if (server.uri.startsWith("turn:")) {
          isTurnPresent = true;
          break;
        }
      }

      // Request TURN servers.
      if (!isTurnPresent) {
        LinkedList<PeerConnection.IceServer> turnServers =
            requestTurnServers(roomJson.getString("turn_url"));
        for (PeerConnection.IceServer turnServer : turnServers) {
          Log.d(TAG, "TurnServer: " + turnServer);
          iceServers.add(turnServer);
        }
      }
      */

      if (sigParamsOK) {
        SignalingParameters params = new SignalingParameters(
                iceServers, initiator,
                clientId, wssUrl, wssPostUrl,
                offerSdp, iceCandidates);
        events.onSignalingParametersReady(params);
      }
    } catch (JSONException e) {
      events.onSignalingParametersError(
          "Room JSON parsing error: " + e.toString());
    } catch (IOException e) {
      events.onSignalingParametersError("Room IO error: " + e.toString());
    }
  }

  private boolean checkTURNServers(List<PeerConnection.IceServer> iceServers){
    boolean hasTURNServerWithCredentials = false;
    for (PeerConnection.IceServer iceServer : iceServers) {
      if ("turn".equals(iceServer.uri.split(":")[0])) {
        if ((iceServer.username != null) && (iceServer.username.length() > 0) &&
            (iceServer.password != null) && (iceServer.password.length() > 0)) {
          hasTURNServerWithCredentials = true;
          break;
        }
      }
    }
    return hasTURNServerWithCredentials;
  }

  // Must be run off the main thread (because the http request is blocking)!
  private List<PeerConnection.IceServer> getCredentialsForTURNServers(String timedLTCUrl, List<PeerConnection.IceServer> iceServers)
          throws IOException, JSONException {
    Log.d(TAG, "Request TURN credentials from: " + timedLTCUrl);
    HttpURLConnection connection = (HttpURLConnection) new URL(timedLTCUrl).openConnection();
    connection.setRequestMethod("POST");
    connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
    connection.setConnectTimeout(TURN_HTTP_TIMEOUT_MS);
    connection.setReadTimeout(TURN_HTTP_TIMEOUT_MS);

    JSONObject paramJSON = new JSONObject();
    // This loginName is only needed for authenticating vs the TURN server.
    paramJSON.put("loginName", "user" + System.currentTimeMillis());

    OutputStream os = connection.getOutputStream();
    os.write(paramJSON.toString().getBytes("UTF-8"));
    os.close();

    int responseCode = connection.getResponseCode();
    if (responseCode != 200) {
      throw new IOException("Non-200 response when requesting TURN credentials from "
              + timedLTCUrl + " : " + connection.getHeaderField(null));
    }
    InputStream responseStream = connection.getInputStream();
    String response = drainStream(responseStream);
    connection.disconnect();
    Log.d(TAG, "TURN credentials response: " + response);
    JSONObject responseJSON = new JSONObject(response);
    String username = responseJSON.getString("username");
    String password = responseJSON.getString("credential");

    List<PeerConnection.IceServer> updatedICEServers =
            new ArrayList<PeerConnection.IceServer>(iceServers.size());
    for (PeerConnection.IceServer iceServer : iceServers) {
      if ("turn".equals(iceServer.uri.split(":")[0])) {
        updatedICEServers.add(new PeerConnection.IceServer(iceServer.uri, username, password));
      } else {
        updatedICEServers.add(iceServer);
      }
    }
    return updatedICEServers;
  }


  // Requests & returns a TURN ICE Server based on a request URL.  Must be run
  // off the main thread!
  private LinkedList<PeerConnection.IceServer> requestTurnServers(String url)
      throws IOException, JSONException {
    LinkedList<PeerConnection.IceServer> turnServers =
        new LinkedList<PeerConnection.IceServer>();
    Log.d(TAG, "Request TURN from: " + url);
    HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
    connection.setConnectTimeout(TURN_HTTP_TIMEOUT_MS);
    connection.setReadTimeout(TURN_HTTP_TIMEOUT_MS);
    int responseCode = connection.getResponseCode();
    if (responseCode != 200) {
      throw new IOException("Non-200 response when requesting TURN server from "
          + url + " : " + connection.getHeaderField(null));
    }
    InputStream responseStream = connection.getInputStream();
    String response = drainStream(responseStream);
    connection.disconnect();
    Log.d(TAG, "TURN response: " + response);
    JSONObject responseJSON = new JSONObject(response);
    String username = responseJSON.getString("username");
    String password = responseJSON.getString("password");
    JSONArray turnUris = responseJSON.getJSONArray("uris");
    for (int i = 0; i < turnUris.length(); i++) {
      String uri = turnUris.getString(i);
      turnServers.add(new PeerConnection.IceServer(uri, username, password));
    }
    return turnServers;
  }

  // Return the list of ICE servers described by a WebRTCPeerConnection
  // configuration string.
  private LinkedList<PeerConnection.IceServer> iceServersFromPCConfigJSON(
      String pcConfig) throws JSONException {
    JSONObject json = new JSONObject(pcConfig);
    JSONArray servers = json.getJSONArray("iceServers");
    LinkedList<PeerConnection.IceServer> ret =
        new LinkedList<PeerConnection.IceServer>();
    for (int i = 0; i < servers.length(); ++i) {
      JSONObject server = servers.getJSONObject(i);
      String url = server.getString("urls");
      String user = server.has("username") ? server.getString("username") : "";
      String credential =
          server.has("credential") ? server.getString("credential") : "";
      ret.add(new PeerConnection.IceServer(url, user, credential));
    }
    return ret;
  }

  // Return the contents of an InputStream as a String.
  private static String drainStream(InputStream in) {
    Scanner s = new Scanner(in).useDelimiter("\\A");
    return s.hasNext() ? s.next() : "";
  }

}
