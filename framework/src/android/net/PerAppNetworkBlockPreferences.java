package android.net;

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.annotation.SuppressLint;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * TODO: description
 * @hide
 */
@SystemApi
@SuppressLint("ParcelCreator")
public final class PerAppNetworkBlockPreferences implements Parcelable {

  private final Set<String> packagesToBlock;

  /**
   * TODO: description
   * @hide
   */
  @SystemApi
  public PerAppNetworkBlockPreferences() {
    packagesToBlock = new HashSet<>();
  }

  /**
   * TODO: description
   * @hide
   */
  @SystemApi
  public PerAppNetworkBlockPreferences(@NonNull Set<String> packagesToBlock) {
    this.packagesToBlock = packagesToBlock;
  }

  /**
   * TODO: description
   * @hide
   */
  @NonNull
  public Set<String> getPackagesToBlock() {
    return new HashSet<>(packagesToBlock);
  }

  @Override
  public String toString() {
    return String.join(",", packagesToBlock);
  }

  /**
   * TODO: description
   * @hide
   */
  @Override
  public void writeToParcel(@NonNull Parcel dest, int flags) {
    dest.writeStringList(new ArrayList<>(packagesToBlock));
  }

  /**
   * TODO: description
   * @hide
   */
  @Override
  public int describeContents() {
    return 0;
  }

  /**
   *
   * @hide
   */
  @NonNull
  public static final Parcelable.Creator<PerAppNetworkBlockPreferences> CREATOR =
      new Parcelable.Creator<PerAppNetworkBlockPreferences>() {
        @Override
        public PerAppNetworkBlockPreferences[] newArray(int size) {
          return new PerAppNetworkBlockPreferences[size];
        }

        @Override
        public PerAppNetworkBlockPreferences createFromParcel(@NonNull android.os.Parcel in) {
          List<String> packages = new ArrayList<>();
          in.readStringList(packages);
          return new PerAppNetworkBlockPreferences(
              new HashSet<String>(packages));
        }
      };

}
