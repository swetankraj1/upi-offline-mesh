// This file is in the 'service' package
package com.demo.upimesh.service;

// MeshPacket = the encrypted payment envelope
import com.demo.upimesh.model.MeshPacket;
// Collection = group of objects (like a list)
import java.util.Collection;
// Map = key-value storage
import java.util.Map;
// ConcurrentHashMap = thread-safe map
// Multiple threads can use simultaneously!
import java.util.concurrent.ConcurrentHashMap;

// NO @Service or @Component here!
// Why? Because we create MULTIPLE VirtualDevices
// (alice, stranger1, stranger2, bridge etc.)
// @Component creates only ONE instance
// We need MANY instances — so no annotation!
// MeshSimulatorService creates them manually!
public class VirtualDevice {

    // Unique name for this phone
    // Examples: "phone-alice", "phone-bridge"
    // Used for logging and identification
    private final String deviceId;

    // Does this phone have internet?
    // false = stuck in basement (most phones)
    // true  = bridge phone that can upload to backend
    // Only bridge phones can settle payments!
    private final boolean hasInternet;

    // Packets this phone is currently holding
    // Key   = packetId (to avoid holding same packet twice)
    // Value = MeshPacket (the actual encrypted payment)
    //
    // Why ConcurrentHashMap?
    // Gossip rounds run in multiple threads
    // Multiple phones sharing packets simultaneously
    // ConcurrentHashMap = thread-safe = no corruption!
    //
    // Why Map not List?
    // Map uses packetId as key
    // putIfAbsent prevents holding same packet twice!
    // List would allow duplicates!
    private final Map<String, MeshPacket> heldPackets =
            new ConcurrentHashMap<>();

    // Constructor — creates a virtual phone
    // Called by MeshSimulatorService on startup
    // Example:
    // new VirtualDevice("phone-alice", false)
    // new VirtualDevice("phone-bridge", true)
    public VirtualDevice(String deviceId, boolean hasInternet) {
        this.deviceId = deviceId;
        this.hasInternet = hasInternet;
    }

    // Returns phone's name
    // Used by MeshSimulatorService for logging
    // "phone-bridge uploaded packet..."
    public String getDeviceId() { return deviceId; }

    // Returns whether phone has internet
    // Used by MeshSimulatorService:
    // "If hasInternet → upload to backend!"
    // "If not → just gossip with neighbors"
    public boolean hasInternet() { return hasInternet; }

    // ═══════════════════════════════════════════════
    // METHOD: hold()
    // WHAT: Phone receives and stores a packet
    // WHO CALLS: MeshSimulatorService during gossip
    //
    // putIfAbsent = only store if not already holding!
    // Phone won't store same packet twice!
    // Like saying: "I already have this letter,
    // no need for another copy!"
    // ═══════════════════════════════════════════════
    public void hold(MeshPacket packet) {

        // Key = packetId (unique ID of packet)
        // Value = the actual MeshPacket
        // putIfAbsent = only add if key not already there!
        // If phone already has this packet → ignore!
        // Prevents infinite loops in gossip!
        heldPackets.putIfAbsent(
                packet.getPacketId(), packet);
    }

    // ═══════════════════════════════════════════════
    // METHOD: getHeldPackets()
    // WHAT: Get all packets this phone is carrying
    // WHO CALLS: MeshSimulatorService during gossip
    //            "What packets do you have?
    //             Let me spread them to neighbors!"
    // Also called by bridge phone to upload to backend
    // ═══════════════════════════════════════════════
    public Collection<MeshPacket> getHeldPackets() {
        // .values() = get all MeshPackets from the map
        // (just the values, not the packetId keys)
        return heldPackets.values();
    }

    // ═══════════════════════════════════════════════
    // METHOD: holds()
    // WHAT: Does this phone already have this packet?
    // WHO CALLS: MeshSimulatorService during gossip
    //            "Do you already have this packet?
    //             No? Let me give it to you!"
    // Prevents sending packet to phone that has it!
    // ═══════════════════════════════════════════════
    public boolean holds(String packetId) {
        // containsKey = is this packetId in our map?
        // true  = already have it → don't send again!
        // false = don't have it → send it!
        return heldPackets.containsKey(packetId);
    }

    // ═══════════════════════════════════════════════
    // METHOD: packetCount()
    // WHAT: How many packets is this phone carrying?
    // WHO CALLS: Dashboard to show mesh state
    // Shows: "phone-alice: 1 packet"
    //        "phone-bridge: 1 packet"
    // ═══════════════════════════════════════════════
    public int packetCount() {
        return heldPackets.size();
    }

    // ═══════════════════════════════════════════════
    // METHOD: clear()
    // WHAT: Wipe all packets from this phone
    // WHO CALLS: MeshSimulatorService.reset()
    //            When dashboard reset button clicked
    //            Clears all phones for fresh demo!
    // ═══════════════════════════════════════════════
    public void clear() {
        heldPackets.clear();
    }
}