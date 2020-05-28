package com.th3rdwave.safeareacontext;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.ContextWrapper;
import android.view.View;
import android.view.ViewParent;
import android.view.ViewTreeObserver;

import com.facebook.react.bridge.ReactContext;
import com.facebook.react.uimanager.UIManagerModule;
import com.facebook.react.views.view.ReactViewGroup;

import java.util.EnumSet;
import java.util.concurrent.atomic.AtomicBoolean;

import androidx.annotation.Nullable;

@SuppressLint("ViewConstructor")
public class SafeAreaView extends ReactViewGroup implements ViewTreeObserver.OnPreDrawListener {
  private SafeAreaViewMode mMode = SafeAreaViewMode.PADDING;
  private @Nullable EdgeInsets mInsets;
  private @Nullable EnumSet<SafeAreaViewEdges> mEdges;
  private @Nullable View mProviderView;

  public SafeAreaView(Context context) {
    super(context);
  }

  /**
   * UIManagerHelper.getReactContext only exists in RN 0.63+ so vendor it here for a while.
   */
  private static ReactContext getReactContext(View view) {
    Context context = view.getContext();
    if (!(context instanceof ReactContext) && context instanceof ContextWrapper) {
      context = ((ContextWrapper) context).getBaseContext();
    }
    return (ReactContext) context;
  }

  private void updateInsets() {
    if (mInsets != null) {
      EnumSet<SafeAreaViewEdges> edges = mEdges != null
              ? mEdges
              : EnumSet.allOf(SafeAreaViewEdges.class);

      SafeAreaViewLocalData localData = new SafeAreaViewLocalData(mInsets, mMode, edges);

      ReactContext reactContext = getReactContext(this);
      UIManagerModule uiManager = reactContext.getNativeModule(UIManagerModule.class);
      if (uiManager != null) {
        uiManager.setViewLocalData(getId(), localData);
        waitForReactLayout();
      }
    }
  }

  private void waitForReactLayout() {
    // Block the main thread until the native module thread is finished with
    // its current tasks. To do this we use the done boolean as a lock and enqueue
    // a task on the native modules thread. When the task runs we can unblock the
    // main thread. This should be safe as long as the native modules thread
    // does not block waiting on the main thread.
    // TODO: Investigate perf impact.
    final AtomicBoolean done = new AtomicBoolean(false);
    getReactContext(this).runOnNativeModulesQueueThread(new Runnable() {
      @Override
      public void run() {
        synchronized (done) {
          done.set(true);
          done.notify();
        }
      }
    });
    synchronized (done) {
      while (!done.get()) {
        try {
          done.wait();
        } catch (InterruptedException e) {
          e.printStackTrace();
        }
      }
    }
  }

  public void setMode(SafeAreaViewMode mode) {
    mMode = mode;
    updateInsets();
  }

  public void setEdges(EnumSet<SafeAreaViewEdges> edges) {
    mEdges = edges;
    updateInsets();
  }

  private boolean maybeUpdateInsets() {
    if (mProviderView == null) {
      return false;
    }
    EdgeInsets edgeInsets = SafeAreaUtils.getSafeAreaInsets(mProviderView);
    if (edgeInsets != null && (mInsets == null || !mInsets.equalsToEdgeInsets(edgeInsets))) {
      mInsets = edgeInsets;
      updateInsets();
      return true;
    }
    return false;
  }

  private View findProvider() {
    ViewParent current = getParent();
    while (current != null) {
      if (current instanceof SafeAreaProvider) {
        return (View) current;
      }
      current = current.getParent();
    }
    return this;
  }

  @Override
  protected void onAttachedToWindow() {
    super.onAttachedToWindow();

    mProviderView = findProvider();

    mProviderView.getViewTreeObserver().addOnPreDrawListener(this);
    maybeUpdateInsets();
  }

  @Override
  protected void onDetachedFromWindow() {
    super.onDetachedFromWindow();

    if (mProviderView != null) {
      mProviderView.getViewTreeObserver().removeOnPreDrawListener(this);
    }
    mProviderView = null;
  }

  @Override
  public boolean onPreDraw() {
    boolean didUpdate = maybeUpdateInsets();
    if (didUpdate) {
      requestLayout();
    }
    return !didUpdate;
  }
}
