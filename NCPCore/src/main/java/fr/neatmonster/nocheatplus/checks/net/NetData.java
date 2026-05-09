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

import java.util.LinkedList;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import fr.neatmonster.nocheatplus.checks.CheckType;
import fr.neatmonster.nocheatplus.checks.access.ACheckData;
import fr.neatmonster.nocheatplus.checks.moving.MovingConfig;
import fr.neatmonster.nocheatplus.checks.moving.MovingData;
import fr.neatmonster.nocheatplus.checks.net.model.DataPacketFlying;
import fr.neatmonster.nocheatplus.checks.net.model.DataPacketInput;
import fr.neatmonster.nocheatplus.checks.net.model.TeleportQueue;
import fr.neatmonster.nocheatplus.compat.BridgeMisc;
import fr.neatmonster.nocheatplus.compat.SchedulerHelper;
import fr.neatmonster.nocheatplus.components.debug.IDebugPlayer;
import fr.neatmonster.nocheatplus.logging.StaticLog;
import fr.neatmonster.nocheatplus.players.DataManager;
import fr.neatmonster.nocheatplus.players.IPlayerData;
import fr.neatmonster.nocheatplus.utilities.CheckUtils;
import fr.neatmonster.nocheatplus.utilities.StringUtil;
import fr.neatmonster.nocheatplus.utilities.ds.count.ActionFrequency;
import fr.neatmonster.nocheatplus.utilities.location.LocUtil;

/**
 * Data for net checks. Some data structures may not be thread-safe, intended
 * for thread-local use. Order of events should make use within packet handlers
 * safe.
 * 
 * @author asofold
 *
 */
public class NetData extends ACheckData {

    private static final long MOVING_TELEPORT_GRACE_MS = 4000L;
    private static final double MOVING_POSITION_MATCH_EPSILON = 0.03125D;
    private static final double MOVING_SERVER_JUMP_DISTANCE = 100.0D;
    private static final double MOVING_SERVER_JUMP_PACKET_DISTANCE = 64.0D;
    private static final long MOVING_TELEPORT_COMMAND_PENDING_MS = 15000L;

    // Reentrant lock.
    private final Lock lock = new ReentrantLock();
    private final Lock locki = new ReentrantLock();

    // AttackFrequency
    public ActionFrequency attackFrequencySeconds = new ActionFrequency(16, 500); //16 buckets each with 500ms duration = 8 seconds

    // FlyingFrequency
    /** All flying packets, use System.currentTimeMillis() for time. */
    public final ActionFrequency flyingFrequencyAll;

    // Moving
    public double movingVL = 0;
    private long lastMovingTeleportTime = 0L;
    private String lastMovingTeleportWorld = null;
    private String lastMovingTeleportCause = "none";
    private double lastMovingTeleportX = 0.0;
    private double lastMovingTeleportY = 0.0;
    private double lastMovingTeleportZ = 0.0;
    private float lastMovingTeleportYaw = 0.0f;
    private float lastMovingTeleportPitch = 0.0f;
    private long lastOutgoingPositionTime = 0L;
    private boolean lastOutgoingPositionTracked = false;
    private int lastOutgoingPositionTeleportId = Integer.MIN_VALUE;
    private double lastOutgoingPositionX = 0.0;
    private double lastOutgoingPositionY = 0.0;
    private double lastOutgoingPositionZ = 0.0;
    private float lastOutgoingPositionYaw = 0.0f;
    private float lastOutgoingPositionPitch = 0.0f;
    private long lastMovingKnownLocationTime = 0L;
    private String lastMovingKnownLocationWorld = null;
    private double lastMovingKnownLocationX = 0.0;
    private double lastMovingKnownLocationY = 0.0;
    private double lastMovingKnownLocationZ = 0.0;
    private float lastMovingKnownLocationYaw = 0.0f;
    private float lastMovingKnownLocationPitch = 0.0f;
    private long lastServerPositionJumpTime = 0L;
    private String lastServerPositionJumpFromWorld = null;
    private String lastServerPositionJumpToWorld = null;
    private double lastServerPositionJumpFromX = 0.0;
    private double lastServerPositionJumpFromY = 0.0;
    private double lastServerPositionJumpFromZ = 0.0;
    private float lastServerPositionJumpFromYaw = 0.0f;
    private float lastServerPositionJumpFromPitch = 0.0f;
    private double lastServerPositionJumpToX = 0.0;
    private double lastServerPositionJumpToY = 0.0;
    private double lastServerPositionJumpToZ = 0.0;
    private float lastServerPositionJumpToYaw = 0.0f;
    private float lastServerPositionJumpToPitch = 0.0f;
    private long lastTeleportCommandTime = 0L;
    private String lastTeleportCommand = "none";
    private String lastTeleportCommandWorld = null;
    private double lastTeleportCommandX = 0.0;
    private double lastTeleportCommandY = 0.0;
    private double lastTeleportCommandZ = 0.0;
    private float lastTeleportCommandYaw = 0.0f;
    private float lastTeleportCommandPitch = 0.0f;

    // KeepAliveFrequency
    /**
     * Last 20 seconds keep alive packets counting. Use lastUpdate() for the
     * time of the last event. System.currentTimeMillis() is used.
     */
    public ActionFrequency keepAliveFreq = new ActionFrequency(20, 1000);
    public long keepAlivePacketTime = 0L;
    public long keepAlivePreviousPacketTime = 0L;
    public long keepAlivePacketDelta = -1L;
    public long keepAlivePacketId = 0L;
    public long keepAlivePreviousPacketId = 0L;
    public boolean keepAlivePacketIdAvailable = false;
    public boolean keepAlivePreviousPacketIdAvailable = false;
    public boolean keepAliveDuplicateId = false;
    public String keepAlivePacketIdType = "none";
    public int keepAlivePacketLongCount = -1;
    public int keepAlivePacketIntCount = -1;
    public boolean keepAlivePacketAsync = false;
    public String keepAlivePacketThread = "unknown";
	
    // Wrong Turn
    public double wrongTurnVL = 0;
    
    // ToggleFrequency
    public double toggleFrequencyVL = 0;
    public ActionFrequency playerActionFreq;
    
    // Shared.
    /**
     * Last time some action was received (keep alive/flying/interaction). Also
     * maintained for fight.godmode.
     */
    public long lastKeepAliveTime = 0L;

    /**
     * Detect teleport-ACK packets, consistency check to only use outgoing
     * position if there has been a PlayerTeleportEvent for it.
     */
    public final TeleportQueue teleportQueue = new TeleportQueue(); // TODO: Consider using one lock per data instance and pass here.

    /**
     * Store past flying packet locations for reference (lock for
     * synchronization). Mainly meant for access to flying packets from the
     * primary thread. Latest packet is first.
     */
    // TODO: Might extend to synchronize with moving events.
    private final LinkedList<DataPacketFlying> flyingQueue = new LinkedList<DataPacketFlying>();
    /** Maximum amount of packets to store. */
    private final LinkedList<DataPacketInput> inputQueue = new LinkedList<DataPacketInput>();
    private final int flyingQueueMaxSize = 60; // Reduce eviction under lag spikes / main-thread stalls.
    /** The maximum of so far already returned sequence values, altered under lock. */
    private long maxSequence = 0;
    /**
     * Sequence cursor for the last flying packet that has been successfully
     * associated with a Bukkit PlayerMoveEvent (typically the packet matching the event "to").
     * <p>
     * Guarded by {@link #lock} to keep it consistent with queue snapshots.
     */
    private long lastMatchedMoveToSequence = 0;

    /** Overall packet frequency. */
    public final ActionFrequency packetFrequency;

    public NetData(final NetConfig config) {
        flyingFrequencyAll = new ActionFrequency(config.flyingFrequencySeconds, 1000L);
        if (config.packetFrequencySeconds <= 2) {
            packetFrequency = new ActionFrequency(config.packetFrequencySeconds * 3, 333);
        }
        else packetFrequency = new ActionFrequency(config.packetFrequencySeconds * 2, 500);
        playerActionFreq = new ActionFrequency(Math.max(1, config.toggleActionSeconds), 1000L);
    }

    public void onJoin(final Player player) {
        resetMovingTeleportDiagnostics();
        teleportQueue.clear();
        clearFlyingQueue();
    }

    public void onLeave(Player player) {
        resetMovingTeleportDiagnostics();
        teleportQueue.clear();
        clearFlyingQueue();
    }

    /**
     * Register a Bukkit teleport for packet-level moving checks.
     */
    public void recordTeleportEvent(final Location loc) {
        recordTeleportEvent(loc, "unknown");
    }

    /**
     * Register a Bukkit teleport for packet-level moving checks.
     */
    public void recordTeleportEvent(final Location loc, final String cause) {
        lastMovingTeleportTime = System.currentTimeMillis();
        lastMovingTeleportCause = cause == null ? "unknown" : cause;
        movingVL *= 0.5;
        clearFlyingQueue();
        if (loc != null) {
            lastMovingTeleportWorld = loc.getWorld() == null ? "null" : loc.getWorld().getName();
            lastMovingTeleportX = loc.getX();
            lastMovingTeleportY = loc.getY();
            lastMovingTeleportZ = loc.getZ();
            lastMovingTeleportYaw = loc.getYaw();
            lastMovingTeleportPitch = loc.getPitch();
            teleportQueue.onTeleportEvent(loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch());
        }
    }

    /**
     * Register every outgoing server position packet, including ones not matched
     * to a Bukkit PlayerTeleportEvent. This catches packet/event ordering races.
     */
    public void recordOutgoingPosition(final double x, final double y, final double z,
                                       final float yaw, final float pitch,
                                       final int teleportId, final boolean tracked,
                                       final long time) {
        lastOutgoingPositionTime = time;
        lastOutgoingPositionTracked = tracked;
        lastOutgoingPositionTeleportId = teleportId;
        lastOutgoingPositionX = x;
        lastOutgoingPositionY = y;
        lastOutgoingPositionZ = z;
        lastOutgoingPositionYaw = yaw;
        lastOutgoingPositionPitch = pitch;
    }

    public void recordKeepAlivePacket(final long time, final boolean idAvailable, final long id,
                                      final String idType, final int longCount, final int intCount,
                                      final boolean async, final String threadName) {
        keepAlivePreviousPacketTime = keepAlivePacketTime;
        keepAlivePreviousPacketId = keepAlivePacketId;
        keepAlivePreviousPacketIdAvailable = keepAlivePacketIdAvailable;
        keepAlivePacketDelta = keepAlivePreviousPacketTime <= 0L ? -1L : time - keepAlivePreviousPacketTime;
        keepAlivePacketTime = time;
        keepAlivePacketIdAvailable = idAvailable;
        keepAlivePacketId = id;
        keepAlivePacketIdType = idType == null ? "none" : idType;
        keepAlivePacketLongCount = longCount;
        keepAlivePacketIntCount = intCount;
        keepAlivePacketAsync = async;
        keepAlivePacketThread = threadName == null ? "unknown" : threadName;
        keepAliveDuplicateId = idAvailable && keepAlivePreviousPacketIdAvailable && id == keepAlivePreviousPacketId;
    }

    /**
     * Track the server-side player location seen by NET_MOVING. If it suddenly
     * jumps far away, stale client packets near the previous server location are
     * very likely plugin-teleport leftovers.
     */
    public void recordMovingKnownLocation(final Location loc, final long now) {
        if (loc == null) {
            return;
        }
        final String world = loc.getWorld() == null ? "null" : loc.getWorld().getName();
        if (lastMovingKnownLocationTime > 0L) {
            final boolean worldChanged = lastMovingKnownLocationWorld == null
                || !lastMovingKnownLocationWorld.equals(world);
            final double distance = distance(lastMovingKnownLocationX, lastMovingKnownLocationY, lastMovingKnownLocationZ,
                    loc.getX(), loc.getY(), loc.getZ());
            if (worldChanged || distance > MOVING_SERVER_JUMP_DISTANCE) {
                lastServerPositionJumpTime = now;
                lastServerPositionJumpFromWorld = lastMovingKnownLocationWorld;
                lastServerPositionJumpFromX = lastMovingKnownLocationX;
                lastServerPositionJumpFromY = lastMovingKnownLocationY;
                lastServerPositionJumpFromZ = lastMovingKnownLocationZ;
                lastServerPositionJumpFromYaw = lastMovingKnownLocationYaw;
                lastServerPositionJumpFromPitch = lastMovingKnownLocationPitch;
                lastServerPositionJumpToWorld = world;
                lastServerPositionJumpToX = loc.getX();
                lastServerPositionJumpToY = loc.getY();
                lastServerPositionJumpToZ = loc.getZ();
                lastServerPositionJumpToYaw = loc.getYaw();
                lastServerPositionJumpToPitch = loc.getPitch();
            }
        }
        lastMovingKnownLocationTime = now;
        lastMovingKnownLocationWorld = world;
        lastMovingKnownLocationX = loc.getX();
        lastMovingKnownLocationY = loc.getY();
        lastMovingKnownLocationZ = loc.getZ();
        lastMovingKnownLocationYaw = loc.getYaw();
        lastMovingKnownLocationPitch = loc.getPitch();
    }

    /**
     * Mark that a command likely to teleport the player has been issued.
     */
    public void recordTeleportCommand(final String command, final Location loc, final long now) {
        lastTeleportCommandTime = now;
        lastTeleportCommand = command == null ? "unknown" : command;
        if (loc != null) {
            lastTeleportCommandWorld = loc.getWorld() == null ? "null" : loc.getWorld().getName();
            lastTeleportCommandX = loc.getX();
            lastTeleportCommandY = loc.getY();
            lastTeleportCommandZ = loc.getZ();
            lastTeleportCommandYaw = loc.getYaw();
            lastTeleportCommandPitch = loc.getPitch();
        }
    }

    public boolean isWithinMovingTeleportGrace(final long now) {
        return lastMovingTeleportTime > 0L
            && now >= lastMovingTeleportTime
            && now - lastMovingTeleportTime <= MOVING_TELEPORT_GRACE_MS;
    }

    public boolean isWithinOutgoingPositionGrace(final long now, final Location knownLocation) {
        return lastOutgoingPositionTime > 0L
            && now >= lastOutgoingPositionTime
            && now - lastOutgoingPositionTime <= MOVING_TELEPORT_GRACE_MS
            && matchesPosition(lastOutgoingPositionX, lastOutgoingPositionY, lastOutgoingPositionZ, knownLocation);
    }

    public boolean isWithinServerPositionJumpGrace(final long now, final Location knownLocation, final Location packetLocation) {
        return lastServerPositionJumpTime > 0L
            && now >= lastServerPositionJumpTime
            && now - lastServerPositionJumpTime <= MOVING_TELEPORT_GRACE_MS
            && distanceToStored(lastServerPositionJumpToX, lastServerPositionJumpToY, lastServerPositionJumpToZ, knownLocation) <= MOVING_SERVER_JUMP_PACKET_DISTANCE
            && distanceToStored(lastServerPositionJumpFromX, lastServerPositionJumpFromY, lastServerPositionJumpFromZ, packetLocation) <= MOVING_SERVER_JUMP_PACKET_DISTANCE;
    }

    public boolean isWithinTeleportCommandGrace(final long now, final Location knownLocation, final Location packetLocation) {
        return lastTeleportCommandTime > 0L
            && now >= lastTeleportCommandTime
            && now - lastTeleportCommandTime <= MOVING_TELEPORT_COMMAND_PENDING_MS
            && distanceToStored(lastTeleportCommandX, lastTeleportCommandY, lastTeleportCommandZ, knownLocation) > MOVING_SERVER_JUMP_DISTANCE
            && distanceToStored(lastTeleportCommandX, lastTeleportCommandY, lastTeleportCommandZ, packetLocation) <= MOVING_SERVER_JUMP_PACKET_DISTANCE;
    }

    public long getMovingTeleportGraceAge(final long now) {
        return lastMovingTeleportTime <= 0L ? -1L : now - lastMovingTeleportTime;
    }

    public long getOutgoingPositionGraceAge(final long now) {
        return lastOutgoingPositionTime <= 0L ? -1L : now - lastOutgoingPositionTime;
    }

    public long getServerPositionJumpGraceAge(final long now) {
        return lastServerPositionJumpTime <= 0L ? -1L : now - lastServerPositionJumpTime;
    }

    public long getTeleportCommandGraceAge(final long now) {
        return lastTeleportCommandTime <= 0L ? -1L : now - lastTeleportCommandTime;
    }

    public String describeMovingTeleportState(final long now, final Location knownLocation, final Location packetLocation) {
        return new StringBuilder(500)
                .append("eventAge=").append(getMovingTeleportGraceAge(now))
                .append(",eventWithinGrace=").append(isWithinMovingTeleportGrace(now))
                .append(",eventCause=").append(lastMovingTeleportCause)
                .append(",eventLoc=").append(formatTeleportEventLocation())
                .append(",eventToKnown=").append(formatStoredDistance(lastMovingTeleportTime, lastMovingTeleportX, lastMovingTeleportY, lastMovingTeleportZ, knownLocation))
                .append(",eventToPacket=").append(formatStoredDistance(lastMovingTeleportTime, lastMovingTeleportX, lastMovingTeleportY, lastMovingTeleportZ, packetLocation))
                .append(",outgoingAge=").append(getOutgoingPositionGraceAge(now))
                .append(",outgoingWithinGrace=").append(isWithinOutgoingPositionGrace(now, knownLocation))
                .append(",outgoingTracked=").append(lastOutgoingPositionTracked)
                .append(",outgoingTeleportId=").append(lastOutgoingPositionTeleportId)
                .append(",outgoingLoc=").append(formatOutgoingPositionLocation())
                .append(",outgoingToKnown=").append(formatStoredDistance(lastOutgoingPositionTime, lastOutgoingPositionX, lastOutgoingPositionY, lastOutgoingPositionZ, knownLocation))
                .append(",outgoingToPacket=").append(formatStoredDistance(lastOutgoingPositionTime, lastOutgoingPositionX, lastOutgoingPositionY, lastOutgoingPositionZ, packetLocation))
                .append(",serverJumpAge=").append(getServerPositionJumpGraceAge(now))
                .append(",serverJumpWithinGrace=").append(isWithinServerPositionJumpGrace(now, knownLocation, packetLocation))
                .append(",serverJumpFrom=").append(formatServerJumpFrom())
                .append(",serverJumpTo=").append(formatServerJumpTo())
                .append(",serverJumpToKnown=").append(formatStoredDistance(lastServerPositionJumpTime, lastServerPositionJumpToX, lastServerPositionJumpToY, lastServerPositionJumpToZ, knownLocation))
                .append(",serverJumpFromPacket=").append(formatStoredDistance(lastServerPositionJumpTime, lastServerPositionJumpFromX, lastServerPositionJumpFromY, lastServerPositionJumpFromZ, packetLocation))
                .append(",commandAge=").append(getTeleportCommandGraceAge(now))
                .append(",commandWithinGrace=").append(isWithinTeleportCommandGrace(now, knownLocation, packetLocation))
                .append(",command=").append(lastTeleportCommand)
                .append(",commandLoc=").append(formatTeleportCommandLocation())
                .append(",commandToKnown=").append(formatStoredDistance(lastTeleportCommandTime, lastTeleportCommandX, lastTeleportCommandY, lastTeleportCommandZ, knownLocation))
                .append(",commandToPacket=").append(formatStoredDistance(lastTeleportCommandTime, lastTeleportCommandX, lastTeleportCommandY, lastTeleportCommandZ, packetLocation))
                .append(",queue=").append(teleportQueue.getDebugState(now))
                .toString();
    }

    public String describeKeepAliveState(final long now) {
        final DataPacketFlying latest = getCurrentFlyingPacket();
        return new StringBuilder(500)
                .append("packetTime=").append(keepAlivePacketTime)
                .append(",packetAge=").append(keepAlivePacketTime <= 0L ? -1L : now - keepAlivePacketTime)
                .append(",previousPacketAge=").append(keepAlivePreviousPacketTime <= 0L ? -1L : now - keepAlivePreviousPacketTime)
                .append(",delta=").append(keepAlivePacketDelta)
                .append(",idAvailable=").append(keepAlivePacketIdAvailable)
                .append(",idType=").append(keepAlivePacketIdType)
                .append(",id=").append(keepAlivePacketIdAvailable ? Long.toString(keepAlivePacketId) : "none")
                .append(",previousId=").append(keepAlivePreviousPacketIdAvailable ? Long.toString(keepAlivePreviousPacketId) : "none")
                .append(",duplicateId=").append(keepAliveDuplicateId)
                .append(",fieldCounts=long:").append(keepAlivePacketLongCount).append("/int:").append(keepAlivePacketIntCount)
                .append(",async=").append(keepAlivePacketAsync)
                .append(",thread=").append(keepAlivePacketThread)
                .append(",lastSharedKeepAliveAge=").append(lastKeepAliveTime <= 0L ? -1L : now - lastKeepAliveTime)
                .append(",teleportAges=event:").append(getMovingTeleportGraceAge(now))
                .append(",outgoing:").append(getOutgoingPositionGraceAge(now))
                .append(",serverJump:").append(getServerPositionJumpGraceAge(now))
                .append(",command:").append(getTeleportCommandGraceAge(now))
                .append(",latestFlying=").append(formatLatestFlying(latest, now))
                .append(",matchedMoveSeq=").append(getLastMatchedMoveToSequence())
                .append(",teleportQueue=").append(teleportQueue.getDebugState(now))
                .toString();
    }

    private String formatLatestFlying(final DataPacketFlying latest, final long now) {
        if (latest == null) {
            return "none";
        }
        return new StringBuilder(160)
                .append("seq=").append(latest.getSequence())
                .append(",age=").append(now - latest.time)
                .append(",tracked=").append(latest.isTracked())
                .append(",type=").append(latest.getSimplifiedContentType())
                .append(",ground=").append(latest.onGround)
                .append(",hcollide=").append(latest.horizontalCollision)
                .append(",hasPos=").append(latest.hasPos)
                .append(",hasLook=").append(latest.hasLook)
                .toString();
    }

    private void resetMovingTeleportDiagnostics() {
        lastMovingTeleportTime = 0L;
        lastMovingTeleportWorld = null;
        lastMovingTeleportCause = "none";
        lastOutgoingPositionTime = 0L;
        lastOutgoingPositionTracked = false;
        lastOutgoingPositionTeleportId = Integer.MIN_VALUE;
        lastMovingKnownLocationTime = 0L;
        lastMovingKnownLocationWorld = null;
        lastServerPositionJumpTime = 0L;
        lastServerPositionJumpFromWorld = null;
        lastServerPositionJumpToWorld = null;
        lastTeleportCommandTime = 0L;
        lastTeleportCommand = "none";
        lastTeleportCommandWorld = null;
    }

    private boolean matchesPosition(final double x, final double y, final double z, final Location loc) {
        return loc != null
            && Math.abs(x - loc.getX()) <= MOVING_POSITION_MATCH_EPSILON
            && Math.abs(y - loc.getY()) <= MOVING_POSITION_MATCH_EPSILON
            && Math.abs(z - loc.getZ()) <= MOVING_POSITION_MATCH_EPSILON;
    }

    private String formatTeleportEventLocation() {
        return lastMovingTeleportTime <= 0L ? "none"
                : new StringBuilder(100).append(lastMovingTeleportWorld).append('@')
                        .append(formatStoredLocation(lastMovingTeleportX, lastMovingTeleportY, lastMovingTeleportZ, lastMovingTeleportYaw, lastMovingTeleportPitch))
                        .toString();
    }

    private String formatOutgoingPositionLocation() {
        return lastOutgoingPositionTime <= 0L ? "none"
                : formatStoredLocation(lastOutgoingPositionX, lastOutgoingPositionY, lastOutgoingPositionZ, lastOutgoingPositionYaw, lastOutgoingPositionPitch);
    }

    private String formatServerJumpFrom() {
        return lastServerPositionJumpTime <= 0L ? "none"
                : new StringBuilder(100).append(lastServerPositionJumpFromWorld).append('@')
                        .append(formatStoredLocation(lastServerPositionJumpFromX, lastServerPositionJumpFromY, lastServerPositionJumpFromZ,
                                lastServerPositionJumpFromYaw, lastServerPositionJumpFromPitch))
                        .toString();
    }

    private String formatServerJumpTo() {
        return lastServerPositionJumpTime <= 0L ? "none"
                : new StringBuilder(100).append(lastServerPositionJumpToWorld).append('@')
                        .append(formatStoredLocation(lastServerPositionJumpToX, lastServerPositionJumpToY, lastServerPositionJumpToZ,
                                lastServerPositionJumpToYaw, lastServerPositionJumpToPitch))
                        .toString();
    }

    private String formatTeleportCommandLocation() {
        return lastTeleportCommandTime <= 0L ? "none"
                : new StringBuilder(100).append(lastTeleportCommandWorld).append('@')
                        .append(formatStoredLocation(lastTeleportCommandX, lastTeleportCommandY, lastTeleportCommandZ,
                                lastTeleportCommandYaw, lastTeleportCommandPitch))
                        .toString();
    }

    private String formatStoredLocation(final double x, final double y, final double z, final float yaw, final float pitch) {
        return new StringBuilder(90)
                .append("x=").append(StringUtil.fdec3.format(x))
                .append(",y=").append(StringUtil.fdec3.format(y))
                .append(",z=").append(StringUtil.fdec3.format(z))
                .append(",yaw=").append(StringUtil.fdec3.format(yaw))
                .append(",pitch=").append(StringUtil.fdec3.format(pitch))
                .toString();
    }

    private String formatStoredDistance(final long storedTime, final double x, final double y, final double z, final Location loc) {
        if (storedTime <= 0L || loc == null) {
            return "none";
        }
        final double xDiff = loc.getX() - x;
        final double yDiff = loc.getY() - y;
        final double zDiff = loc.getZ() - z;
        return new StringBuilder(80)
                .append("h=").append(StringUtil.fdec3.format(Math.sqrt(xDiff * xDiff + zDiff * zDiff)))
                .append("/y=").append(StringUtil.fdec3.format(Math.abs(yDiff)))
                .append("/total=").append(StringUtil.fdec3.format(Math.sqrt(xDiff * xDiff + yDiff * yDiff + zDiff * zDiff)))
                .toString();
    }

    private double distanceToStored(final double x, final double y, final double z, final Location loc) {
        return loc == null ? Double.MAX_VALUE : distance(x, y, z, loc.getX(), loc.getY(), loc.getZ());
    }

    private double distance(final double x1, final double y1, final double z1,
                            final double x2, final double y2, final double z2) {
        final double xDiff = x2 - x1;
        final double yDiff = y2 - y1;
        final double zDiff = z2 - z1;
        return Math.sqrt(xDiff * xDiff + yDiff * yDiff + zDiff * zDiff);
    }
    
    /**
     * Safely request a set back from MovingData.
     * 
     * @param player
     * @param idp
     * @param plugin
     * @param checkType
     */
    public void requestSetBack(final Player player, final IDebugPlayer idp, final Plugin plugin, final CheckType checkType) {
        final IPlayerData pData = DataManager.getPlayerData(player);
        /** Last known location that has been registered by Bukkit. */
        final Location knownLocation = player.getLocation();
        final MovingData mData = pData.getGenericInstance(MovingData.class);
        Object task = null;
        // Packet-level set backs must run on the player's owning region on Folia.
        task = SchedulerHelper.runSyncTaskForEntity(player, plugin, (arg) -> {
            /** Get the first set-back location that might be available */
            final Location newTo = mData.hasSetBack() ? mData.getSetBack(knownLocation) :
                                   mData.hasMorePacketsSetBack() ? mData.getMorePacketsSetBack() :
                                   // Shouldn't happen. If it does, the location is likely to be null
                                   knownLocation;
            // Unsafe position. Location hasn't been updated yet.  
            if (newTo == null) {
                StaticLog.logSevere("Could not retrieve a safe (set back) location for " + player.getName() + " on packet-level, kicking them due to crash potential.");
                CheckUtils.kickIllegalMove(player, pData.getGenericInstance(MovingConfig.class));
            } 
            else {
                // Mask player teleport as a set back.
                mData.prepareSetBack(newTo);
                SchedulerHelper.teleportEntity(player, LocUtil.clone(newTo), BridgeMisc.TELEPORT_CAUSE_CORRECTION_OF_POSITION);
                if (pData.isDebugActive(checkType)) {
                    idp.debug(player, "Packet set back tasked for player: " + player.getName() + " at :" + LocUtil.simpleFormat(newTo));
                }
            }
        }, null);
        if (!SchedulerHelper.isTaskScheduled(task)) {
            StaticLog.logWarning("Failed to schedule packet set back task for player: " + player.getName());
        }
        mData.resetTeleported(); // Cleanup, just in case.
    }

    /**
     * Add a packet to the queue (under lock). The sequence number of the packet
     * will be set here, according to a count maintained per-data.
     * 
     * @param packetData
     * @return If a packet has been removed due to exceeding maximum size.
     */
    public boolean addFlyingQueue(final DataPacketFlying packetData) {
        boolean res = false;
        lock.lock();
        packetData.setSequence(++maxSequence);
        flyingQueue.addFirst(packetData);
        if (flyingQueue.size() > flyingQueueMaxSize) {
            flyingQueue.removeLast();
            res = true;
        }
        lock.unlock();
        locki.lock();
        inputQueue.addFirst(null);
        if (inputQueue.size() > flyingQueueMaxSize + 1) {
            inputQueue.removeLast();
        }
        locki.unlock();
        return res;
    }

    /**
     * Update packet to first entry the queue (under lock).
     * 
     * @param packetData
     */
    public void addInputQueue(final DataPacketInput packetData) {
        locki.lock();
        if (inputQueue.isEmpty()) {
            inputQueue.add(packetData);
            locki.unlock();
            return;
        }
        inputQueue.set(0, packetData);
        locki.unlock();
    }

    /**
     * Clear the flying/input packet queue (under lock).
     */
    public void clearFlyingQueue() {
        lock.lock();
        flyingQueue.clear();
        maxSequence = 0;
        lastMatchedMoveToSequence = 0;
        lock.unlock();

        locki.lock();
        inputQueue.clear();
        locki.unlock();
    }

    /**
     * Get the last matched move "to" sequence cursor (under lock).
     *
     * @return 0 if never set or after reset.
     */
    public long getLastMatchedMoveToSequence() {
        lock.lock();
        final long out = lastMatchedMoveToSequence;
        lock.unlock();
        return out;
    }

    /**
     * Update the last matched move "to" sequence cursor (under lock).
     * Only increases the value (monotonic), to stay safe on unusual control flow.
     *
     * @param sequence Sequence to set as "last matched to".
     */
    public void updateLastMatchedMoveToSequence(final long sequence) {
        if (sequence <= 0) {
            return;
        }
        lock.lock();
        if (sequence > lastMatchedMoveToSequence) {
            lastMatchedMoveToSequence = sequence;
        }
        lock.unlock();
    }
    /**
     * Copy the entire flying queue (under lock).
     * 
     * @return
     */
    public DataPacketFlying[] copyFlyingQueue() {
        lock.lock();
        /*
         * TODO: Add a method to synchronize with the current position at the
         * same time ? Packet inversion is acute on 1.11.2 (dig is processed
         * before flying).
         */
        final DataPacketFlying[] out = flyingQueue.toArray(new DataPacketFlying[flyingQueue.size()]);
        lock.unlock();
        return out;
    }

    /**
     * Copy the entire input queue (under lock).
     * 
     * @return
     */
    public DataPacketInput[] copyInputQueue() {
        locki.lock();
        final DataPacketInput[] out = inputQueue.toArray(new DataPacketInput[inputQueue.size()]);
        locki.unlock();
        return out;
    }

    /**
     * Fetch the latest packet (under lock).
     * 
     * @return
     */
    public DataPacketFlying getCurrentFlyingPacket() {
        lock.lock();
        final DataPacketFlying latest = flyingQueue.isEmpty() ? null : flyingQueue.getFirst();
        lock.unlock();
        return latest;
    }

    /**
     * Fetch a past packet in queue (under lock).
     * @param index 0 is current 1 is first past packet.
     * @return
     */
    public DataPacketFlying getPastFlyingPacketInQueue(final int index) {
        lock.lock();
        final DataPacketFlying packet = flyingQueue.isEmpty() ? null : flyingQueue.get(index);
        lock.unlock();
        return packet;
    }

    /**
     * (Not implementing the interface, to avoid confusion.)
     */
    public void handleSystemTimeRanBackwards() {
        final long now = System.currentTimeMillis();
        teleportQueue.clear(); // Can't handle timeouts. TODO: Might still keep.
        lastKeepAliveTime = Math.min(lastKeepAliveTime, now);
        // (Keep flyingQueue.)
        // (ActionFrequency can handle this.)
    }
}
