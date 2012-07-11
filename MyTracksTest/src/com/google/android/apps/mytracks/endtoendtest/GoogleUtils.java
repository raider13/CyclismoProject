/*
 * Copyright 2012 Google Inc.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.android.apps.mytracks.endtoendtest;

import com.google.android.apps.mytracks.Constants;
import com.google.android.apps.mytracks.io.docs.SendDocsUtils;
import com.google.android.apps.mytracks.io.fusiontables.SendFusionTablesAsyncTask;
import com.google.android.apps.mytracks.io.fusiontables.SendFusionTablesUtils;
import com.google.android.apps.mytracks.io.gdata.GDataClientFactory;
import com.google.android.apps.mytracks.io.gdata.docs.DocumentsClient;
import com.google.android.apps.mytracks.io.gdata.docs.SpreadsheetsClient;
import com.google.android.apps.mytracks.io.gdata.docs.XmlDocsGDataParserFactory;
import com.google.android.apps.mytracks.io.gdata.maps.MapFeatureEntry;
import com.google.android.apps.mytracks.io.gdata.maps.MapsClient;
import com.google.android.apps.mytracks.io.gdata.maps.MapsConstants;
import com.google.android.apps.mytracks.io.gdata.maps.MapsGDataConverter;
import com.google.android.apps.mytracks.io.gdata.maps.MapsMapMetadata;
import com.google.android.apps.mytracks.io.gdata.maps.XmlMapsGDataParserFactory;
import com.google.android.apps.mytracks.util.ApiAdapterFactory;
import com.google.android.apps.mytracks.util.SystemUtils;
import com.google.android.common.gdata.AndroidXmlParserFactory;
import com.google.api.client.googleapis.GoogleHeaders;
import com.google.api.client.googleapis.MethodOverride;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpResponse;
import com.google.api.client.http.InputStreamContent;
import com.google.api.client.util.Strings;
import com.google.wireless.gdata.data.Entry;
import com.google.wireless.gdata.data.Feed;
import com.google.wireless.gdata.parser.GDataParser;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.Activity;
import android.content.Context;
import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Locale;

/**
 * Provides utilities to access Google Maps, Google Documents, Google Fusion
 * Tables.
 * 
 * @author Youtao Liu
 */
public class GoogleUtils {
  private static Account account;
  public static final String LOG_TAG = "MyTracksTest";
  public static final String DOCUMENT_NAME_PREFIX = "My Tracks";
  public static final String SPREADSHEET_NAME = DOCUMENT_NAME_PREFIX + "-" + EndToEndTestUtils.DEFAULTACTIVITY;

  private static Account getAccount(Context context) {
    return account == null ? account = AccountManager.get(context).getAccountsByType(
        Constants.ACCOUNT_TYPE)[0] : account;
  }
  
  
  /**
   * Gets Google maps of a user.
   * 
   * @param context used to get maps
   * @param mapsClient the client to access Google Maps
   * @return true means set successfully
   */
  private static ArrayList<MapsMapMetadata> getMaps(Context context, MapsClient mapsClient) {
    // Reset the per request states
    String authToken = null;
    ArrayList<String> mapIds = new ArrayList<String>();
    ArrayList<MapsMapMetadata> mapData = new ArrayList<MapsMapMetadata>();

    try {
      authToken = AccountManager.get(context).blockingGetAuthToken(getAccount(context),
          MapsConstants.SERVICE_NAME, false);
    } catch (Exception e) {
      Log.d(LOG_TAG, "Unable to get auth token", e);
      return mapData;
    }

    GDataParser gDataParser = null;
    try {
      gDataParser = mapsClient.getParserForFeed(MapFeatureEntry.class, MapsClient.getMapsFeed(),
          authToken);
      gDataParser.init();
      while (gDataParser.hasMoreData()) {
        MapFeatureEntry entry = (MapFeatureEntry) gDataParser.readNextEntry(null);
        mapIds.add(MapsGDataConverter.getMapidForEntry(entry));
        mapData.add(MapsGDataConverter.getMapMetadataForEntry(entry));
      }
    } catch (Exception e) {
      Log.d(LOG_TAG, "Unable to get maps", e);
    } finally {
      if (gDataParser != null) {
        gDataParser.close();
      }
    }

    return mapData;
  }

  /**
   * Searches a map in user's Google Maps.
   * 
   * @param title the title of map
   * @param activity activity to get context
   * @param isDelete whether delete the map of this track in the Google Maps
   * @return true means find the map
   */
  private static boolean searchMapByTitle(String title, Activity activity, boolean isDelete) {
    Context context = activity.getApplicationContext();
    MapsClient mapsClient = new MapsClient(GDataClientFactory.getGDataClient(context),
        new XmlMapsGDataParserFactory(new AndroidXmlParserFactory()));
    ArrayList<MapsMapMetadata> mapData = getMaps(context, mapsClient);
    for (MapsMapMetadata oneData : mapData) {
      if (oneData.getDescription().indexOf(DOCUMENT_NAME_PREFIX) > -1
          && oneData.getTitle().equals(title)) {
        if (isDelete) {
          try {
            mapsClient.deleteEntry(oneData.getGDataEditUri(), AccountManager.get(context).blockingGetAuthToken(getAccount(context),
                MapsConstants.SERVICE_NAME, false));
            return true;
          } catch (Exception e) {
            Log.d(LOG_TAG, "Unable to drop map", e);
            return false;
          }
        }
        return true;
      }
    }
    return false;
  }
  
  /**
   * Searches a map in user's Google Maps.
   * 
   * @param title the title of map
   * @param activity activity to get context
   * @return true means find the map
   */
  public static boolean searchMap(String title, Activity activity) {
    return searchMapByTitle(title, activity, false);
  }
  
  /**
   * Searches a map in user's Google Maps and then delete it.
   * 
   * @param title the title of map
   * @param activity activity to get context
   * @return true means find the map and delete it successfully
   */
  public static boolean deleteMap(String title, Activity activity) {
    return searchMapByTitle(title, activity, true);
  }

  /**
   * Searches a doc in user's Google Documents.
   * 
   * @param title the title of doc
   * @param activity to get context
   * @return the entry of the document, null means can not find the spreadsheet.
   */
  private static Entry searchSepeadsheetByTitle(String title, Activity activity) {
    Context context = activity.getApplicationContext(); 
    DocumentsClient documentsClient = new DocumentsClient(
        GDataClientFactory.getGDataClient(context),
        new XmlDocsGDataParserFactory(new AndroidXmlParserFactory()));
    
    try {
      String documentsAuthToken = AccountManager.get(context)
          .blockingGetAuthToken(getAccount(context), documentsClient.getServiceName(), false);
      String uri = String.format(Locale.US, SendDocsUtils.GET_SPREADSHEET_BY_TITLE_URI,
          URLEncoder.encode(title, "utf-8"));
      GDataParser gDataParser = documentsClient.getParserForFeed(Entry.class, uri,
          documentsAuthToken);
      gDataParser.init();

      while (gDataParser.hasMoreData()) {
        Entry entry = gDataParser.readNextEntry(null);
        String entryTitle = entry.getTitle();
        if (entryTitle.equals(title)) { 
          return entry; 
        }
      }
    } catch (Exception e) {
      Log.d(LOG_TAG, "Unable to fetch spreadsheet.", e);
    }
    return null;
  }
  
  
  /**
   * Searches a track title in a spreadsheet.
   * 
   * @param title the track name to search
   * @param activity to get context
   * @param spreadsheetTitle the title of spreadsheet
   * @param isDelete whether delete the information of this track in the document
   * @return true means find the track name in the spreadsheet
   */
  public static boolean searchTrackTitleInSpreadsheet(String title, Activity activity, String spreadsheetTitle, boolean isDelete) {
    String spreadsheetId = searchSepeadsheetByTitle(spreadsheetTitle, activity).getId().replace(SendDocsUtils.SPREADSHEET_ID_PREFIX, "");
    if(spreadsheetId == null) {
      Log.d(LOG_TAG, "Unable to find the spreadsheet -- " + spreadsheetTitle);
      return false;
    }
    Context context = activity.getApplicationContext();
    try {
    SpreadsheetsClient spreadsheetsClient = new SpreadsheetsClient(
        GDataClientFactory.getGDataClient(context), new XmlDocsGDataParserFactory(new AndroidXmlParserFactory()));
    String spreadsheetsAuthToken = AccountManager.get(activity.getApplicationContext()).blockingGetAuthToken(
        getAccount(context), spreadsheetsClient.getServiceName(), false);
    
    String weekSheetId = SendDocsUtils.getWorksheetId(spreadsheetId, spreadsheetsClient, spreadsheetsAuthToken);
    String worksheetUri = String.format(Locale.US, SendDocsUtils.GET_WORKSHEET_URI, URLEncoder.encode(spreadsheetId, "utf-8"),
        weekSheetId);

    GDataParser gDataParser = spreadsheetsClient.getParserForFeed(Feed.class, worksheetUri,
        spreadsheetsAuthToken);
    gDataParser.init();
    while (gDataParser.hasMoreData()) {
      Entry entry = gDataParser.readNextEntry(null);
      String entryTitle = entry.getTitle();
      if (entryTitle.indexOf(title) > -1) { 
        if (isDelete) {
          spreadsheetsClient.deleteEntry(entry.getEditUri(), spreadsheetsAuthToken);
        }
        return true;
      }
    }
    } catch (Exception e) {
      Log.d(LOG_TAG, "Unable to fetch content of spreadsheet.", e);
    }
    return false;
  }

  /**
   * Searches a fusion table in user's Google tables.
   * 
   * @param title the title of fusion table
   * @param activity to get context
   * @return true means find the fusion table
   */
  public static boolean searchFusionTableByTitle(String title, Activity activity) {
    Context context = activity.getApplicationContext();
    try {
      HttpResponse response = sendFusionTableQuery("SHOW TABLES", context);
      // We can use index of method to check new table for every track name is unique.
      if (response != null && response.parseAsString().indexOf(title) > 0) { 
        return true; 
      }
    } catch (Exception e) {
      Log.d(LOG_TAG, "Unable to query fusion table.", e);
    }
    return false;
  }
  
  /**
   * Drops one fusion table which contain the string in title of current user.
   * 
   * @param title the title of a track to drop 
   * @param activity to get context
   */
  public static boolean dropFusionTables(String title, Activity activity) {
    Context context = activity.getApplicationContext();

    HttpResponse response = sendFusionTableQuery("SHOW TABLES", context);
    String[] rowsTable;
    try {
      rowsTable = response.parseAsString().split("\n");
      for (String row : rowsTable) {
        String firstColumn = row.split(",")[0];
        String secondColumn = row.split(",")[1];
        // If the first column is all figure, it is the table id.
        String regularExpression = "^[0-9]*$";
        if (firstColumn.matches(regularExpression) && secondColumn.equals(title)) {
          sendFusionTableQuery("DROP TABLE " + row.split(",")[0], context);
          return true; 
        }
      }
    } catch (IOException e) {
      Log.d(LOG_TAG, "Failed when delete all fusion tables.", e);
    }
    return false;
  }
  
  /**
   * Sends query to operate fusion tables.
   * 
   * @param query to executed
   * @param context application context
   * @return the response of execution
   */
  private static HttpResponse sendFusionTableQuery(String query, Context context) {
    try {
      String fusionTableAuthToken = AccountManager.get(context).blockingGetAuthToken(getAccount(context),
          SendFusionTablesUtils.SERVICE, false);

      GenericUrl url = new GenericUrl(SendFusionTablesAsyncTask.FUSION_TABLES_BASE_URL);
      String sql = "sql=" + query;
      ByteArrayInputStream inputStream = new ByteArrayInputStream(Strings.toBytesUtf8(sql));
      InputStreamContent inputStreamContent = new InputStreamContent(null, inputStream);
      HttpRequest request;

      request = (ApiAdapterFactory.getApiAdapter().getHttpTransport()
          .createRequestFactory(new MethodOverride())).buildPostRequest(url, inputStreamContent);

      GoogleHeaders headers = new GoogleHeaders();
      headers.setApplicationName(SendFusionTablesAsyncTask.APP_NAME_PREFIX
          + SystemUtils.getMyTracksVersion(context));
      headers.gdataVersion = SendFusionTablesAsyncTask.GDATA_VERSION;
      headers.setGoogleLogin(fusionTableAuthToken);
      headers.setContentType(SendFusionTablesAsyncTask.CONTENT_TYPE);
      request.setHeaders(headers);

      HttpResponse response;
      response = request.execute();
      return response;
    } catch (Exception e) {
      Log.d(LOG_TAG, "Failed when send fusion table query.", e);
      return null;
    }
  }

}