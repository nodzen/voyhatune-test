package com.qinggan.launcher.base.bean;

import android.graphics.drawable.Drawable;

import java.util.List;

/**
 * Synthetic launcher-base value object matching the AppBean surface used by launcherdock.js.
 * Resource values are inert placeholders; this harness does not render the OEM launcher.
 */
public class AppBean {
    private final int icon;
    private final int nameRes;
    private final String packageName;
    private final int type;
    private List<Object> appBeans;
    private String appName;
    private Drawable dynamicDrawable;
    private String subType;

    public AppBean(int icon, int nameRes, String packageName) {
        this.icon = icon;
        this.nameRes = nameRes;
        this.packageName = packageName;
        this.type = 1;
    }

    public AppBean(int icon, String appName, String packageName) {
        this.icon = icon;
        this.nameRes = 0;
        this.appName = appName;
        this.packageName = packageName;
        this.type = 1;
    }

    public int getIcon() {
        return icon;
    }

    public int getNameRes() {
        return nameRes;
    }

    public String getPackageName() {
        return packageName;
    }

    public String getAppName() {
        return appName;
    }

    public Drawable getDynamicDrawable() {
        return dynamicDrawable;
    }

    public boolean isDynamicApp() {
        return appName != null;
    }

    public void setDynamicDrawable(Drawable dynamicDrawable) {
        this.dynamicDrawable = dynamicDrawable;
    }

    public int getType() {
        return type;
    }

    public List<Object> getAppBeans() {
        return appBeans;
    }

    public String getSubType() {
        return subType;
    }

    public void setSubType(String subType) {
        this.subType = subType;
    }
}
