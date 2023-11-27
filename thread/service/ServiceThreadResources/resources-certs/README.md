Certificates were generated via the following commands as described in go/android-apex-howto.

1. openssl req -x509 -newkey rsa:4096 -nodes -days 999999 -keyout key.pem -out com.android.thread.resources.x509.pem

2. openssl pkcs8 -topk8 -inform PEM -outform DER -in key.pem -out com.android.thread.resources.pk8 -nocrypt

