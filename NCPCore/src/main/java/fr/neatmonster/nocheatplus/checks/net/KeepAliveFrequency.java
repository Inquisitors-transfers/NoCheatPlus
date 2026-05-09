/*
 * This program is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   This program is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package fr.neatmonster.nocheatplus.checks.net;

import org.bukkit.entity.Player;

import fr.neatmonster.nocheatplus.checks.Check;
import fr.neatmonster.nocheatplus.checks.CheckType;
import fr.neatmonster.nocheatplus.players.IPlayerData;
import fr.neatmonster.nocheatplus.utilities.CheckUtils;
import fr.neatmonster.nocheatplus.utilities.StringUtil;
import fr.neatmonster.nocheatplus.utilities.TickTask;

public class KeepAliveFrequency extends Check {

    public KeepAliveFrequency() {
        super(CheckType.NET_KEEPALIVEFREQUENCY);
    }
    
    /**
     * Checks hasBypass on violation only.
     * @param player
     * @param time
     * @param data
     * @param cc
     * @return If to cancel.
     */
    public boolean check(final Player player, final long time, final NetData data, final NetConfig cc, final IPlayerData pData) {
        final long now = System.currentTimeMillis();
        final long joinTime = pData.getLastJoinTime();
        if (joinTime > 0L && now < joinTime + cc.keepAliveFrequencyStartupDelay) {
            return false;
        }
        data.keepAliveFreq.add(time, 1f);
        final float first = data.keepAliveFreq.bucketScore(0);
        
        if (first > 1f) {
            // Trigger a violation.
            final float fullScore = data.keepAliveFreq.score(1f);
            if (isBucketBoundaryGrace(data, fullScore, first)) {
                return false;
            }
            final double vl = Math.max(first - 1f, fullScore - data.keepAliveFreq.numberOfBuckets());
            final boolean cancel = executeActions(player, vl, 1.0, cc.keepAliveFrequencyActions).willCancel();
            if (CheckUtils.shouldLogDebugToConsole()) {
                logConsoleDetails(player, time, now, joinTime, data, cc, pData, first, fullScore, vl, cancel);
            }
            return cancel;
        }
        return false;
    }

    private boolean isBucketBoundaryGrace(final NetData data, final float fullScore, final float first) {
        return first <= 2f
                && data.keepAlivePacketDelta >= 750L
                && fullScore <= data.keepAliveFreq.numberOfBuckets() + 1f
                && !data.keepAliveDuplicateId;
    }

    private void logConsoleDetails(final Player player, final long packetTime, final long now, final long joinTime,
                                   final NetData data, final NetConfig cc, final IPlayerData pData,
                                   final float first, final float fullScore, final double vl,
                                   final boolean cancel) {
        try {
            final long bucketDuration = data.keepAliveFreq.bucketDuration();
            final int buckets = data.keepAliveFreq.numberOfBuckets();
            player.getServer().getLogger().info(new StringBuilder(900)
                    .append("[NCP][KeepAliveFrequency][detail] player=").append(player.getName())
                    .append(" uuid=").append(player.getUniqueId())
                    .append(" client=").append(pData.getClientVersion())
                    .append(" packetTime=").append(packetTime)
                    .append(" now=").append(now)
                    .append(" joinAge=").append(joinTime <= 0L ? -1L : now - joinTime)
                    .append(" startupDelay=").append(cc.keepAliveFrequencyStartupDelay)
                    .append(" vl=").append(StringUtil.fdec3.format(vl))
                    .append(" cancel=").append(cancel)
                    .append(" firstBucket=").append(StringUtil.fdec3.format(first))
                    .append(" fullScore=").append(StringUtil.fdec3.format(fullScore))
                    .append(" expectedFull=").append(buckets)
                    .append(" bucketDuration=").append(bucketDuration)
                    .append(" bucketAge=").append(now - data.keepAliveFreq.lastAccess())
                    .append(" lastUpdateAge=").append(now - data.keepAliveFreq.lastUpdate())
                    .append(" lag1s=").append(StringUtil.fdec3.format(TickTask.getLag(bucketDuration, true)))
                    .append(" lagWindow=").append(StringUtil.fdec3.format(TickTask.getLag(bucketDuration * buckets, true)))
                    .append(" buckets=").append(formatBuckets(data, Math.min(6, buckets)))
                    .append(" state=").append(data.describeKeepAliveState(now))
                    .toString());
        }
        catch (Throwable ignored) {}
    }

    private String formatBuckets(final NetData data, final int limit) {
        final StringBuilder builder = new StringBuilder(80);
        builder.append('[');
        for (int i = 0; i < limit; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(StringUtil.fdec3.format(data.keepAliveFreq.bucketScore(i)));
        }
        if (limit < data.keepAliveFreq.numberOfBuckets()) {
            builder.append(",...");
        }
        builder.append(']');
        return builder.toString();
    }
}
