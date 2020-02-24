// Copyright 2015-present 650 Industries. All rights reserved.

package versioned.host.exp.exponent.modules.api.notifications;

import com.cronutils.model.Cron;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableMap;
import com.google.android.gms.gcm.GoogleCloudMessaging;
import com.google.android.gms.iid.InstanceID;
import com.google.firebase.iid.FirebaseInstanceId;

import org.json.JSONException;
import org.json.JSONObject;

import java.security.InvalidParameterException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import javax.inject.Inject;

import host.exp.exponent.Constants;
import host.exp.exponent.ExponentManifest;
import host.exp.exponent.analytics.EXL;
import host.exp.exponent.di.NativeModuleDepsProvider;
import host.exp.exponent.network.ExponentNetwork;
import host.exp.exponent.notifications.ExponentNotificationManager;
import host.exp.exponent.notifications.NotificationActionCenter;
import host.exp.exponent.notifications.NotificationConstants;
import host.exp.exponent.notifications.NotificationHelper;
import host.exp.exponent.notifications.schedulers.IntervalSchedulerModel;
import host.exp.exponent.notifications.schedulers.SchedulerImpl;
import host.exp.exponent.storage.ExponentSharedPreferences;
import host.exp.exponent.notifications.exceptions.UnableToScheduleException;
import host.exp.exponent.notifications.managers.SchedulersManagerProxy;
import host.exp.exponent.notifications.schedulers.CalendarSchedulerModel;

import static com.cronutils.model.field.expression.FieldExpressionFactory.on;
import static host.exp.exponent.notifications.helpers.ExpoCronParser.createCronInstance;

public class NotificationsModule extends ReactContextBaseJavaModule {

  private static final String TAG = NotificationsModule.class.getSimpleName();

  @Inject
  ExponentSharedPreferences mExponentSharedPreferences;

  @Inject
  ExponentManifest mExponentManifest;

  @Inject
  ExponentNetwork mExponentNetwork;

  private final JSONObject mManifest;

  public NotificationsModule(ReactApplicationContext reactContext,
                             JSONObject manifest, Map<String, Object> experienceProperties) {
    super(reactContext);
    NativeModuleDepsProvider.getInstance().inject(NotificationsModule.class, this);
    mManifest = manifest;
  }

  @Override
  public String getName() {
    return "ExponentNotifications";
  }

  @ReactMethod
  public void createCategoryAsync(final String categoryIdParam, final ReadableArray actions, final Promise promise) {
    String categoryId = getScopedIdIfNotDetached(categoryIdParam);
    List<Map<String, Object>> newActions = new ArrayList<>();

    for (Object actionObject : actions.toArrayList()) {
      if (actionObject instanceof Map) {
        Map<String, Object> action = (Map<String, Object>) actionObject;
        newActions.add(action);
      }
    }

    NotificationActionCenter.putCategory(categoryId, newActions);
    promise.resolve(null);
  }

  @ReactMethod
  public void deleteCategoryAsync(final String categoryIdParam, final Promise promise) {
    String categoryId = getScopedIdIfNotDetached(categoryIdParam);
    NotificationActionCenter.removeCategory(categoryId);
    promise.resolve(null);
  }

  private String getScopedIdIfNotDetached(String categoryId) {
    if (!Constants.isStandaloneApp()) {
      try {
        String experienceId = ExponentManifest.getExperienceId(mManifest);
        return experienceId + ":" + categoryId;
      } catch (JSONException e) {
        e.printStackTrace();
      }
    }
    return categoryId;
  }

  @ReactMethod
  public void getDevicePushTokenAsync(final ReadableMap config, final Promise promise) {
    if (!Constants.isStandaloneApp()) {
      promise.reject("getDevicePushTokenAsync is only accessible within standalone applications");
    }
    try {
      if (Constants.FCM_ENABLED) {
        String token = FirebaseInstanceId.getInstance().getToken();
        if (token == null) {
          promise.reject("FCM token has not been set");
        } else {
          WritableMap params = Arguments.createMap();
          params.putString("type", "fcm");
          params.putString("data", token);
          promise.resolve(params);
        }
      } else {
        InstanceID instanceID = InstanceID.getInstance(this.getReactApplicationContext());
        String gcmSenderId = config.getString("gcmSenderId");
        if (gcmSenderId == null || gcmSenderId.length() == 0) {
          throw new InvalidParameterException("GCM Sender ID is null/empty");
        }
        final String token = instanceID.getToken(gcmSenderId, GoogleCloudMessaging.INSTANCE_ID_SCOPE, null);
        if (token == null) {
          promise.reject("GCM token has not been set");
        } else {
          WritableMap params = Arguments.createMap();
          params.putString("type", "gcm");
          params.putString("data", token);
          promise.resolve(params);
        }
      }
    } catch (Exception e) {
      EXL.e(TAG, e.getMessage());
      promise.reject(e.getMessage());
    }
  }

  @ReactMethod
  public void getExponentPushTokenAsync(final Promise promise) {
    String uuid = mExponentSharedPreferences.getUUID();
    if (uuid == null) {
      // This should have been set by ExponentNotificationIntentService when Activity was created/resumed.
      promise.reject("Couldn't get GCM token on device.");
      return;
    }

    try {
      String legacyExperienceId = mManifest.getString(ExponentManifest.MANIFEST_ID_KEY);
      String projectId = mManifest.optString(ExponentManifest.MANIFEST_SCOPE_KEY_KEY);
      NotificationHelper.getPushNotificationToken(uuid, legacyExperienceId, projectId, mExponentNetwork, mExponentSharedPreferences, new NotificationHelper.TokenListener() {
        @Override
        public void onSuccess(String token) {
          promise.resolve(token);
        }

        @Override
        public void onFailure(Exception e) {
          promise.reject("E_GET_GCM_TOKEN_FAILED", "Couldn't get GCM token for device", e);
        }
      });
    } catch (JSONException e) {
      promise.reject("E_GET_GCM_TOKEN_FAILED", "Couldn't get GCM token for device", e);
      return;
    }
  }

  @ReactMethod
  public void createChannel(String channelId, final ReadableMap data, final Promise promise) {
    String experienceId;
    String channelName;

    try {
      experienceId = ExponentManifest.getExperienceId(mManifest);
    } catch (Exception e) {
      promise.reject("E_FAILED_CREATING_CHANNEL", "Requires Experience ID");
      return;
    }

    if (data.hasKey(NotificationConstants.NOTIFICATION_CHANNEL_NAME)) {
      channelName = data.getString(NotificationConstants.NOTIFICATION_CHANNEL_NAME);
    } else {
      promise.reject("E_FAILED_CREATING_CHANNEL", "Requires channel name");
      return;
    }

    try {
      NotificationHelper.createChannel(
          getReactApplicationContext(),
          experienceId,
          channelId,
          channelName,
          data.toHashMap());
      promise.resolve(null);
    } catch (Exception e) {
      promise.reject("E_FAILED_CREATING_CHANNEL", "Could not create channel", e);
    }
  }

  @ReactMethod
  public void deleteChannel(String channelId, final Promise promise) {
    String experienceId;

    try {
      experienceId = ExponentManifest.getExperienceId(mManifest);
    } catch (Exception e) {
      promise.reject("E_FAILED_DELETING_CHANNEL", "Requires Experience ID");
      return;
    }

    try {
      NotificationHelper.deleteChannel(
          getReactApplicationContext(),
          experienceId,
          channelId);
      promise.resolve(null);
    } catch (Exception e) {
      promise.reject("E_FAILED_DELETING_CHANNEL", "Could not delete channel", e);
    }
  }

  @ReactMethod
  public void presentLocalNotification(final ReadableMap data, final Promise promise) {
    presentLocalNotificationWithChannel(data, null, promise);
  }

  @ReactMethod
  public void presentLocalNotificationWithChannel(final ReadableMap data, final ReadableMap legacyChannelData, final Promise promise) {
    HashMap<String, java.io.Serializable> details = new HashMap<>();
    String experienceId;

    HashMap<String, Object> hashMap = data.toHashMap();
    if (data.hasKey("categoryId")) {
      hashMap.put("categoryId", getScopedIdIfNotDetached(data.getString("categoryId")));
    }

    details.put("data", hashMap);

    try {
      experienceId = ExponentManifest.getExperienceId(mManifest);
      details.put("experienceId", experienceId);
    } catch (Exception e) {
      promise.reject("E_FAILED_PRESENTING_NOTIFICATION", "Requires Experience ID");
      return;
    }

    if (legacyChannelData != null) {
      String channelId = data.getString("channelId");
      if (channelId == null) {
        promise.reject("E_FAILED_PRESENTING_NOTIFICATION", "legacyChannelData was nonnull with no channelId");
        return;
      }
      NotificationHelper.maybeCreateLegacyStoredChannel(
          getReactApplicationContext(),
          experienceId,
          channelId,
          legacyChannelData.toHashMap());
    }

    int notificationId = new Random().nextInt();

    NotificationHelper.showNotification(
        getReactApplicationContext(),
        notificationId,
        details,
        mExponentManifest,
        new NotificationHelper.Listener() {
          public void onSuccess(int id) {
            promise.resolve(id);
          }

          public void onFailure(Exception e) {
            promise.reject(e);
          }
        });
  }

  @ReactMethod
  public void scheduleLocalNotification(final ReadableMap data, final ReadableMap options, final Promise promise) {
    scheduleLocalNotificationWithChannel(data, options, null, promise);
  }

  @ReactMethod
  public void scheduleLocalNotificationWithChannel(final ReadableMap data, final ReadableMap options, final ReadableMap legacyChannelData, final Promise promise) {
    if (legacyChannelData != null) {
      String experienceId = ExponentManifest.getExperienceIdOrNull(mManifest);
      String channelId = data.getString("channelId");
      if (channelId == null || experienceId == null) {
        promise.reject("E_FAILED_PRESENTING_NOTIFICATION", "legacyChannelData was nonnull with no channelId or no experienceId");
        return;
      }
      NotificationHelper.maybeCreateLegacyStoredChannel(
          getReactApplicationContext(),
          experienceId,
          channelId,
          legacyChannelData.toHashMap());
    }

    int notificationId = new Random().nextInt();

    HashMap<String, Object> hashMap = data.toHashMap();
    if (data.hasKey("categoryId")) {
      hashMap.put("categoryId", getScopedIdIfNotDetached(data.getString("categoryId")));
    }

    NotificationHelper.scheduleLocalNotification(
        getReactApplicationContext(),
        notificationId,
        hashMap,
        options.toHashMap(),
        mManifest,
        new NotificationHelper.Listener() {
          public void onSuccess(int id) {
            promise.resolve(id);
          }

          public void onFailure(Exception e) {
            promise.reject(e);
          }
        });
  }

  @ReactMethod
  public void dismissNotification(final int notificationId, final Promise promise) {
    try {
      ExponentNotificationManager manager = new ExponentNotificationManager(getReactApplicationContext());
      manager.cancel(
          ExponentManifest.getExperienceId(mManifest),
          notificationId
      );
      promise.resolve(true);
    } catch (JSONException e) {
      promise.reject(e);
    }
  }

  @ReactMethod
  public void dismissAllNotifications(final Promise promise) {
    try {
      ExponentNotificationManager manager = new ExponentNotificationManager(getReactApplicationContext());
      manager.cancelAll(ExponentManifest.getExperienceId(mManifest));
      promise.resolve(true);
    } catch (JSONException e) {
      promise.reject(e);
    }
  }

  @ReactMethod
  public void cancelScheduledNotificationAsync(final int notificationId, final Promise promise) {
    try {
      ExponentNotificationManager manager = new ExponentNotificationManager(getReactApplicationContext());
      manager.cancelScheduled(ExponentManifest.getExperienceId(mManifest), notificationId);
      promise.resolve(null);
    } catch (Exception e) {
      promise.reject(e);
    }
  }

  @ReactMethod
  public void cancelScheduledNotificationWithStringIdAsync(final String id, final Promise promise) {
    try {
      SchedulersManagerProxy.getInstance(getReactApplicationContext()
          .getApplicationContext())
          .removeScheduler(id);
      promise.resolve(null);
    } catch (Exception e) {
      promise.reject(e);
    }
  }

  @ReactMethod
  public void cancelAllScheduledNotificationsAsync(final Promise promise) {
    try {
      ExponentNotificationManager manager = new ExponentNotificationManager(getReactApplicationContext());
      manager.cancelAllScheduled(ExponentManifest.getExperienceId(mManifest));

      String experienceId = ExponentManifest.getExperienceIdOrNull(mManifest);

      SchedulersManagerProxy
          .getInstance(getReactApplicationContext().getApplicationContext())
          .removeAll(experienceId);

      promise.resolve(null);
    } catch (Exception e) {
      promise.reject(e);
    }
  }

  @ReactMethod
  public void scheduleNotificationWithTimer(final ReadableMap data, final ReadableMap optionsMap, final Promise promise) {
    HashMap<String, Object> options = optionsMap.toHashMap();
    int notificationId = Math.abs( new Random().nextInt() );
    HashMap<String, Object> hashMap = data.toHashMap();
    if (data.hasKey("categoryId")) {
      hashMap.put("categoryId", getScopedIdIfNotDetached(data.getString("categoryId")));
    }
    HashMap<String, Object> details = new HashMap<>();
    details.put("data", hashMap);
    String experienceId;

    try {
      experienceId = ExponentManifest.getExperienceId(mManifest);
      details.put("experienceId", experienceId);
    } catch (Exception e) {
      promise.reject(new Exception("Requires Experience Id"));
      return;
    }

    IntervalSchedulerModel intervalSchedulerModel = new IntervalSchedulerModel();
    intervalSchedulerModel.setExperienceId(experienceId);
    intervalSchedulerModel.setNotificationId(notificationId);
    intervalSchedulerModel.setDetails(details);
    intervalSchedulerModel.setRepeat(options.containsKey("repeat") && (Boolean) options.get("repeat"));
    intervalSchedulerModel.setScheduledTime(System.currentTimeMillis() + ((Double) options.get("interval")).longValue());
    intervalSchedulerModel.setInterval(((Double) options.get("interval")).longValue()); // on iOS we cannot change interval

    SchedulerImpl scheduler = new SchedulerImpl(intervalSchedulerModel);

    SchedulersManagerProxy.getInstance(getReactApplicationContext().getApplicationContext()).addScheduler(
        scheduler,
        (String id) -> {
          if (id == null) {
            promise.reject(new UnableToScheduleException());
            return false;
          }
          promise.resolve(id);
          return true;
        }
    );
  }
  
  @ReactMethod
  public void scheduleNotificationWithCalendar(final ReadableMap data, final ReadableMap optionsMap, final Promise promise) {
    HashMap<String, Object> options = optionsMap.toHashMap();
    int notificationId = Math.abs( new Random().nextInt() );
    HashMap<String, Object> hashMap = data.toHashMap();
    if (data.hasKey("categoryId")) {
      hashMap.put("categoryId", getScopedIdIfNotDetached(data.getString("categoryId")));
    }
    HashMap<String, Object> details = new HashMap<>();
    details.put("data", hashMap);
    String experienceId;

    try {
      experienceId = ExponentManifest.getExperienceId(mManifest);
      details.put("experienceId", experienceId);
    } catch (Exception e) {
      promise.reject(new Exception("Requires Experience Id"));
      return;
    }

    Cron cron = createCronInstance(options);

    CalendarSchedulerModel calendarSchedulerModel = new CalendarSchedulerModel();
    calendarSchedulerModel.setExperienceId(experienceId);
    calendarSchedulerModel.setNotificationId(notificationId);
    calendarSchedulerModel.setDetails(details);
    calendarSchedulerModel.setRepeat(options.containsKey("repeat") && (Boolean) options.get("repeat"));
    calendarSchedulerModel.setCalendarData(cron.asString());

    SchedulerImpl scheduler = new SchedulerImpl(calendarSchedulerModel);

    SchedulersManagerProxy.getInstance(getReactApplicationContext().getApplicationContext()).addScheduler(
        scheduler,
        (String id) -> {
          if (id == null) {
            promise.reject(new UnableToScheduleException());
            return false;
          }
          promise.resolve(id);
          return true;
        }
    );
  }

}
