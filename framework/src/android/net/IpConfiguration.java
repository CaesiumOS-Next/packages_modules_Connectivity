/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.net;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.compat.annotation.UnsupportedAppUsage;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * A class representing the IP configuration of a network.
 */
public final class IpConfiguration implements Parcelable {
    private static final String TAG = "IpConfiguration";

    // This enum has been used by apps through reflection for many releases.
    // Therefore they can't just be removed. Duplicating these constants to
    // give an alternate SystemApi is a worse option than exposing them.
    /** @hide */
    @SystemApi
    @SuppressLint("Enum")
    public enum IpAssignment {
        /* Use statically configured IP settings. Configuration can be accessed
         * with staticIpConfiguration */
        STATIC,
        /* Use dynamically configured IP settings */
        DHCP,
        /* no IP details are assigned, this is used to indicate
         * that any existing IP settings should be retained */
        UNASSIGNED
    }

    /** Use statically configured IP settings via the
     * {@link #setStaticIpConfiguration(StaticIpConfiguration)}. */
    public static final int IP_ASSIGNMENT_STATIC = 0;

    /** Use dynamically configured IP settings. */
    public static final int IP_ASSIGNMENT_DHCP = 1;

    /** No IP details are assigned. Use to indicate that any existing IP settings should be
     * retained. */
    public static final int IP_ASSIGNMENT_UNASSIGNED = 2;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"IP_ASSIGNMENT_"}, value = {
            IP_ASSIGNMENT_STATIC,
            IP_ASSIGNMENT_DHCP,
            IP_ASSIGNMENT_UNASSIGNED
    }) public @interface IpAssignmentType {}

    /** convert IP assignment legacy enums to IntDef types */
    private static @IpAssignmentType int ipAssignmentToIntDef(IpAssignment enumValue) {
        switch (enumValue) {
            case STATIC: return IP_ASSIGNMENT_STATIC;
            case DHCP: return IP_ASSIGNMENT_DHCP;
            case UNASSIGNED: return IP_ASSIGNMENT_UNASSIGNED;
        }
        return IP_ASSIGNMENT_UNASSIGNED; /* bad enum! */
    }

    /** concert IP assignment IntDef type to legacy enum */
    private static IpAssignment ipAssignmentIntDefToEnum(@IpAssignmentType int intValue) {
        switch (intValue) {
            case IP_ASSIGNMENT_STATIC: return IpAssignment.STATIC;
            case IP_ASSIGNMENT_DHCP: return IpAssignment.DHCP;
            case IP_ASSIGNMENT_UNASSIGNED: return IpAssignment.UNASSIGNED;
        }
        return IpAssignment.UNASSIGNED; /* bad IntDef value! */
    }

    /** @hide */
    public IpAssignment ipAssignment;

    /** @hide */
    public StaticIpConfiguration staticIpConfiguration;

    // This enum has been used by apps through reflection for many releases.
    // Therefore they can't just be removed. Duplicating these constants to
    // give an alternate SystemApi is a worse option than exposing them.
    /** @hide */
    @SystemApi
    @SuppressLint("Enum")
    public enum ProxySettings {
        /* No proxy is to be used. Any existing proxy settings
         * should be cleared. */
        NONE,
        /* Use statically configured proxy. Configuration can be accessed
         * with httpProxy. */
        STATIC,
        /* no proxy details are assigned, this is used to indicate
         * that any existing proxy settings should be retained */
        UNASSIGNED,
        /* Use a Pac based proxy.
         */
        PAC
    }

    /** No proxy is to be sued. Any existing proxy settings should be cleared. */
    public static final int PROXY_SETTING_NONE = 0;

    /** Use statically configured proxy via {@link #setHttpProxy(ProxyInfo)}. */
    public static final int PROXY_SETTING_STATIC = 1;

    /** No proxy details are assigned. Used to indicate that any existing proxy settings should be
     * retained. */
    public static final int PROXY_SETTING_UNASSIGNED = 2;

    /** Use a PAC-based proxy. */
    public static final int PROXY_SETTING_PAC = 3;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"PROXY_SETTING_"}, value = {
            PROXY_SETTING_NONE,
            PROXY_SETTING_STATIC,
            PROXY_SETTING_UNASSIGNED,
            PROXY_SETTING_PAC
    }) public @interface ProxySettingType {}

    /** convert proxy setting legacy enums to IntDef types */
    private static @ProxySettingType int proxySettingToIntDef(ProxySettings enumValue) {
        switch (enumValue) {
            case NONE: return PROXY_SETTING_NONE;
            case STATIC: return PROXY_SETTING_STATIC;
            case UNASSIGNED: return PROXY_SETTING_UNASSIGNED;
            case PAC: return PROXY_SETTING_PAC;
        }
        return PROXY_SETTING_UNASSIGNED; /* bad enum! */
    }

    /** concert proxy setting IntDef type to legacy enum */
    private static ProxySettings proxySettingIntDefToEnum(@ProxySettingType int intValue) {
        switch (intValue) {
            case PROXY_SETTING_NONE: return ProxySettings.NONE;
            case PROXY_SETTING_STATIC: return ProxySettings.STATIC;
            case PROXY_SETTING_UNASSIGNED: return ProxySettings.UNASSIGNED;
            case PROXY_SETTING_PAC: return ProxySettings.PAC;
        }
        return ProxySettings.UNASSIGNED; /* bad IntDef value! */
    }

    /** @hide */
    public ProxySettings proxySettings;

    /** @hide */
    @UnsupportedAppUsage
    public ProxyInfo httpProxy;

    private void init(IpAssignment ipAssignment,
                      ProxySettings proxySettings,
                      StaticIpConfiguration staticIpConfiguration,
                      ProxyInfo httpProxy) {
        this.ipAssignment = ipAssignment;
        this.proxySettings = proxySettings;
        this.staticIpConfiguration = (staticIpConfiguration == null) ?
                null : new StaticIpConfiguration(staticIpConfiguration);
        this.httpProxy = (httpProxy == null) ?
                null : new ProxyInfo(httpProxy);
    }

    public IpConfiguration() {
        init(IpAssignment.UNASSIGNED, ProxySettings.UNASSIGNED, null, null);
    }

    /** @hide */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public IpConfiguration(IpAssignment ipAssignment,
                           ProxySettings proxySettings,
                           StaticIpConfiguration staticIpConfiguration,
                           ProxyInfo httpProxy) {
        init(ipAssignment, proxySettings, staticIpConfiguration, httpProxy);
    }

    public IpConfiguration(@NonNull IpConfiguration source) {
        this();
        if (source != null) {
            init(source.ipAssignment, source.proxySettings,
                 source.staticIpConfiguration, source.httpProxy);
        }
    }

    /** @hide */
    @SystemApi
    public @NonNull IpAssignment getIpAssignment() {
        return ipAssignment;
    }

    /**
     * Return the type of the IP assignment represented by this object.
     */
    public @IpAssignmentType int getIpAssignmentType() {
        return ipAssignmentToIntDef(getIpAssignment());
    }

    /** @hide */
    @SystemApi
    public void setIpAssignment(@NonNull IpAssignment ipAssignment) {
        this.ipAssignment = ipAssignment;
    }

    /**
     * Configure the IP assignment type represented by this object.
     *
     * @param ipAssignment An IP assignment type.
     */
    public void setIpAssignmentType(@IpAssignmentType int ipAssignment) {
        setIpAssignment(ipAssignmentIntDefToEnum(ipAssignment));
    }

    /**
     * Returns the static IP configuration of this object. Only used if the IP assignment type is
     * {@link #IP_ASSIGNMENT_STATIC} as configured via {@link #setIpAssignmentType(int)}.
     *
     * @return The static IP configuration of this object.
     */
    public @Nullable StaticIpConfiguration getStaticIpConfiguration() {
        return staticIpConfiguration;
    }

    /**
     * Set the static IP configuration of this object. Only used if the IP assignment type is
     * {@link #IP_ASSIGNMENT_STATIC} as configured via {@link #setIpAssignmentType(int)}.
     *
     * @param staticIpConfiguration The static IP configuration of this object.
     */
    public void setStaticIpConfiguration(@Nullable StaticIpConfiguration staticIpConfiguration) {
        this.staticIpConfiguration = staticIpConfiguration;
    }

    /** @hide */
    @SystemApi
    public @NonNull ProxySettings getProxySettings() {
        return proxySettings;
    }

    /** @hide */
    @SystemApi
    public void setProxySettings(@NonNull ProxySettings proxySettings) {
        this.proxySettings = proxySettings;
    }

    /**
     * Return the type of the proxy setting represented by this object.
     */
    public @ProxySettingType int getProxySettingType() {
        return proxySettingToIntDef(getProxySettings());
    }

    /**
     * Configure the proxy setting type represented by this object.
     *
     * @param proxySetting A proxy setting type.
     */
    public void setProxySettingType(@ProxySettingType int proxySetting) {
        setProxySettings(proxySettingIntDefToEnum(proxySetting));
    }

    /**
     * The proxy configuration of this object. Only valid if {@link #setProxySettingType(int)} is
     * used to configure the proxy type to {@link #PROXY_SETTING_STATIC} or
     * {@link #PROXY_SETTING_PAC}.
     *
     * @return The proxy information of this object configured via {@link #setHttpProxy(ProxyInfo)}.
     */
    public @Nullable ProxyInfo getHttpProxy() {
        return httpProxy;
    }

    /**
     * Set the proxy configuration of this object. Only valid if {@link #setProxySettingType(int)}
     * is used to configure the proxy type to {@link #PROXY_SETTING_STATIC} or
     * {@link #PROXY_SETTING_PAC}.
     *
     * @param httpProxy The proxy information.
     */
    public void setHttpProxy(@Nullable ProxyInfo httpProxy) {
        this.httpProxy = httpProxy;
    }

    @Override
    public String toString() {
        StringBuilder sbuf = new StringBuilder();
        sbuf.append("IP assignment: " + ipAssignment.toString());
        sbuf.append("\n");
        if (staticIpConfiguration != null) {
            sbuf.append("Static configuration: " + staticIpConfiguration.toString());
            sbuf.append("\n");
        }
        sbuf.append("Proxy settings: " + proxySettings.toString());
        sbuf.append("\n");
        if (httpProxy != null) {
            sbuf.append("HTTP proxy: " + httpProxy.toString());
            sbuf.append("\n");
        }

        return sbuf.toString();
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (o == this) {
            return true;
        }

        if (!(o instanceof IpConfiguration)) {
            return false;
        }

        IpConfiguration other = (IpConfiguration) o;
        return this.ipAssignment == other.ipAssignment &&
                this.proxySettings == other.proxySettings &&
                Objects.equals(this.staticIpConfiguration, other.staticIpConfiguration) &&
                Objects.equals(this.httpProxy, other.httpProxy);
    }

    @Override
    public int hashCode() {
        return 13 + (staticIpConfiguration != null ? staticIpConfiguration.hashCode() : 0) +
               17 * ipAssignment.ordinal() +
               47 * proxySettings.ordinal() +
               83 * httpProxy.hashCode();
    }

    /** Implement the Parcelable interface */
    public int describeContents() {
        return 0;
    }

    /** Implement the Parcelable interface */
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(ipAssignment.name());
        dest.writeString(proxySettings.name());
        dest.writeParcelable(staticIpConfiguration, flags);
        dest.writeParcelable(httpProxy, flags);
    }

    /** Implement the Parcelable interface */
    public static final @NonNull Creator<IpConfiguration> CREATOR =
        new Creator<IpConfiguration>() {
            public IpConfiguration createFromParcel(Parcel in) {
                IpConfiguration config = new IpConfiguration();
                config.ipAssignment = IpAssignment.valueOf(in.readString());
                config.proxySettings = ProxySettings.valueOf(in.readString());
                config.staticIpConfiguration = in.readParcelable(null);
                config.httpProxy = in.readParcelable(null);
                return config;
            }

            public IpConfiguration[] newArray(int size) {
                return new IpConfiguration[size];
            }
        };
}
