package com.zoffcc.applications.trifa;

/**
 * KHANDAQ (external audit: exported ConnectionManager receiver) — what an incoming connectivity
 * broadcast is allowed to decide.
 *
 * <p>ConnectionManager is declared {@code android:exported="true"} in the manifest because the
 * legacy {@code CONNECTIVITY_CHANGE} delivery on API 21-23 comes from the system, i.e. from another
 * uid. Export cuts both ways: any app on the device can also send an <em>explicit</em> intent naming
 * the component, with extras of its choosing. The action itself is a protected broadcast — the
 * framework refuses to let a third party send {@code CONNECTIVITY_CHANGE} at all — so an attacker's
 * intent necessarily arrives with a different action, or none.
 *
 * <p>That is the whole defence, and it only works if the action is checked <em>before</em> any extra
 * is read or any work is scheduled. Two rules follow, and both are enforced here rather than inline
 * so they can be tested without an Android runtime (same reason as {@link NgcHistorySyncBudget}):
 *
 * <ol>
 *   <li>Exact action match. Not "starts with", not "non-null" — an intent whose action is null or
 *       anything else is not a connectivity broadcast and must be ignored.</li>
 *   <li>The reconnect decision is taken from the connectivity state the app holds after asking
 *       ConnectivityManager, not from a boolean the caller supplied. {@code EXTRA_IS_FAILOVER} is
 *       out of the decision entirely — it was already dead code, see the comment in
 *       {@code ConnectionManager.onReceive} — which leaves exactly one input, and it is one the
 *       system owns.</li>
 * </ol>
 *
 * <p>This is behaviour-preserving for a genuine broadcast: the old expression
 * {@code HAVE && (failOver || !noConnectivity)}, evaluated after {@code noConnectivity} had already
 * forced {@code HAVE} to false, reduces to {@code HAVE} — which is what {@link
 * #shouldScheduleReconnect} computes.
 */
final class ConnectivityBroadcastPolicy
{
    /** {@code ConnectivityManager.CONNECTIVITY_ACTION}, spelled out so this class needs no Android. */
    static final String CONNECTIVITY_ACTION = "android.net.conn.CONNECTIVITY_CHANGE";

    private ConnectivityBroadcastPolicy()
    {
    }

    /** True only for the exact legacy connectivity broadcast. Null and every other action: false. */
    static boolean accepts(final String action)
    {
        return CONNECTIVITY_ACTION.equals(action);
    }

    /**
     * @param action                 the intent's action, as received.
     * @param haveInternetFromSystem what ConnectivityManager says right now — NOT an intent extra.
     * @return whether this broadcast should schedule a reconnect.
     */
    static boolean shouldScheduleReconnect(final String action, final boolean haveInternetFromSystem)
    {
        return accepts(action) && haveInternetFromSystem;
    }
}
