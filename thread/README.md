# Thread

Bring the [Thread](https://www.threadgroup.org/) networking protocol to Android.

## Try Thread with Cuttlefish

```
# Get the code and go to the Androit root dir

source build/envsetup.sh
lunch aosp_cf_x86_64_phone-trunk_staging-userdebug
m && m ThreadNetworkDemoApp

launch_cvd

# In another terminal session
adb install -r -t out/target/product/vsoc_x86_64/system/priv-app/ThreadNetworkDemoApp/ThreadNetworkDemoApp.apk
```

Open `https://localhost:8443/` in your web browser, you can find the Thread
demoapp in the cuttlefish instance. Open it and have fun with Thread!
