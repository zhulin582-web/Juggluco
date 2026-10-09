// SPDX-License-Identifier: GPL-3.0-or-later
package tk.glucodata;

import androidx.annotation.Keep;

/** One consistent snapshot, populated by JNI. Times are UTC seconds. */
@Keep
public final class ClarityStatus {
    public boolean available, enabled, libre3History, numbers;
    public long since, lastSuccess, acknowledgedCount, acknowledgedLast;
    public long pendingCount, pendingFirst, pendingLast;
    public String account = "", status = "";
}
