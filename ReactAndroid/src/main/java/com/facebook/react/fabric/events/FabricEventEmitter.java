/**
 * Copyright (c) 2014-present, Facebook, Inc.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.react.fabric.events;

import static com.facebook.react.uimanager.events.TouchesHelper.CHANGED_TOUCHES_KEY;
import static com.facebook.react.uimanager.events.TouchesHelper.TARGET_KEY;
import static com.facebook.react.uimanager.events.TouchesHelper.TOP_TOUCH_CANCEL_KEY;
import static com.facebook.react.uimanager.events.TouchesHelper.TOP_TOUCH_END_KEY;
import static com.facebook.react.uimanager.events.TouchesHelper.TOUCHES_KEY;

import android.annotation.TargetApi;
import android.os.Build;
import android.util.Log;
import android.util.Pair;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.bridge.WritableNativeArray;
import com.facebook.react.bridge.WritableNativeMap;
import com.facebook.react.fabric.FabricUIManager;
import com.facebook.react.fabric.Scheduler;
import com.facebook.react.fabric.Work;
import com.facebook.react.uimanager.events.RCTEventEmitter;
import java.io.Closeable;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import javax.annotation.Nullable;

@TargetApi(Build.VERSION_CODES.ECLAIR)
public class FabricEventEmitter implements RCTEventEmitter, Closeable {

  private static final String TAG = FabricEventEmitter.class.getSimpleName();

  private final FabricUIManager mFabricUIManager;
  private final Scheduler mScheduler;

  public FabricEventEmitter(ReactApplicationContext context, FabricUIManager fabricUIManager) {
    mScheduler = new Scheduler(context);
    mFabricUIManager = fabricUIManager;
  }

  @Override
  public void receiveEvent(int targetTag, String eventName, @Nullable WritableMap params) {
    //TODO get instanceHandle associated with targetTag.
    int instanceHandle = targetTag;
    mScheduler.scheduleWork(new FabricUIManagerWork(instanceHandle, eventName, params) );
  }

  @Override
  public void close() {
    mScheduler.shutdown();
  }

  private class FabricUIManagerWork implements Work {
    private final int mInstanceHandle;
    private final String mEventName;
    private final WritableMap mParams;

    public FabricUIManagerWork(int instanceHandle, String eventName, @Nullable WritableMap params) {
      mInstanceHandle = instanceHandle;
      mEventName = eventName;
      mParams = params;
    }

    @Override
    public void run() {
      mFabricUIManager.invoke(mInstanceHandle, mEventName, mParams);
    }
  }

  @Override
  public void receiveTouches(String eventTopLevelType, WritableArray touches,
    WritableArray changedIndices) {
    Pair<WritableArray, WritableArray> result =
      TOP_TOUCH_END_KEY.equalsIgnoreCase(eventTopLevelType) ||
        TOP_TOUCH_CANCEL_KEY.equalsIgnoreCase(eventTopLevelType)
        ? removeTouchesAtIndices(touches, changedIndices)
        : touchSubsequence(touches, changedIndices);

    WritableArray changedTouches = result.first;
    touches = result.second;

    for (int jj = 0; jj < changedTouches.size(); jj++) {
      WritableMap touch = getWritableMap(changedTouches.getMap(jj));
      // Touch objects can fulfill the role of `DOM` `Event` objects if we set
      // the `changedTouches`/`touches`. This saves allocations.
      touch.putArray(CHANGED_TOUCHES_KEY, changedTouches);
      touch.putArray(TOUCHES_KEY, touches);
      WritableMap nativeEvent = touch;
      int rootNodeID = 0;
      int target = nativeEvent.getInt(TARGET_KEY);
      if (target < 1) {
        Log.e(TAG,"A view is reporting that a touch occurred on tag zero.");
      } else {
        rootNodeID = target;
      }
      receiveEvent(rootNodeID, eventTopLevelType, touch);
    }
  }

  /**
   * Destroys `touches` by removing touch objects at indices `indices`. This is
   * to maintain compatibility with W3C touch "end" events, where the active
   * touches don't include the set that has just been "ended".
   *
   * This method was originally in ReactNativeRenderer.js
   *
   * TODO: this method is a copy from ReactNativeRenderer.removeTouchesAtIndices and it needs
   * to be rewritten in a more efficient way,
   *
   * @param touches {@link WritableArray} Deserialized touch objects.
   * @param indices {WritableArray} Indices to remove from `touches`.
   * @return {Array<Touch>} Subsequence of removed touch objects.
   */
  private Pair<WritableArray, WritableArray> removeTouchesAtIndices(WritableArray touches, WritableArray indices) {
    WritableArray rippedOut = new WritableNativeArray();
    // use an unsafe downcast to alias to nullable elements,
    // so we can delete and then compact.
    WritableArray tempTouches = new WritableNativeArray();
    Set<Integer> rippedOutIndices = new HashSet<>();
    for (int i = 0; i < indices.size(); i++) {
      int index = indices.getInt(i);
      rippedOut.pushMap(getWritableMap(touches.getMap(index)));
      rippedOutIndices.add(index);
    }
    for (int j = 0 ; j < touches.size() ; j++) {
      if (!rippedOutIndices.contains(j)) {
        tempTouches.pushMap(getWritableMap(touches.getMap(j)));
      }
    }

    return new Pair<>(rippedOut, tempTouches);
  }

  /**
   * Selects a subsequence of `Touch`es, without destroying `touches`.
   *
   * This method was originally in ReactNativeRenderer.js
   *
   * @param touches {@link WritableArray} Deserialized touch objects.
   * @param changedIndices {@link WritableArray} Indices by which to pull subsequence.
   * @return {Array<Touch>} Subsequence of touch objects.
   */
  private Pair<WritableArray, WritableArray> touchSubsequence(WritableArray touches, WritableArray changedIndices) {
    WritableArray result = new WritableNativeArray();
    for (int i = 0; i < changedIndices.size(); i++) {
      result.pushMap(getWritableMap(touches.getMap(i)));
    }
    return new Pair<>(result, touches);
  }

  /**
   * TODO: this is required because the WritableNativeArray.getMap() returns a ReadableMap instead
   * of the original writableMap. this will change in the near future.
   *
   * @param readableMap {@link ReadableMap} source map
   */
  private WritableMap getWritableMap(ReadableMap readableMap) {
    WritableNativeMap map = new WritableNativeMap();
    map.merge(readableMap);
    return map;
  }
}