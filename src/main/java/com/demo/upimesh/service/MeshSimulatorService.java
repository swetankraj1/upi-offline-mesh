// This file is in the 'service' package
package com.demo.upimesh.service;

import com.demo.upimesh.model.MeshPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

// @Service = Spring creates ONE instance
// This is the entire mesh network simulation!
// Controls all virtual phones and packet spreading
@Service
public class MeshSimulatorService {

    private static final Logger log =
            LoggerFactory.getLogger(MeshSimulatorService.class);

    // All virtual phones in our mesh
    // Key   = deviceId ("phone-alice", "phone-bridge")
    // Value = VirtualDevice object
    // ConcurrentHashMap = thread-safe
    // Multiple gossip rounds can run safely!
    private final Map<String, VirtualDevice> devices =
            new ConcurrentHashMap<>();

    // Constructor — runs when Spring creates this service
    // Immediately creates our 5 virtual phones!
    // No @PostConstruct needed because we have
    // no @Autowired dependencies to wait for!
    public MeshSimulatorService() {
        seedDefaultDevices();
    }

    // ═══════════════════════════════════════════════
    // METHOD: seedDefaultDevices()
    // WHAT: Creates the 5 virtual phones
    // Called once when app starts
    // ═══════════════════════════════════════════════
    private void seedDefaultDevices() {

        // 4 offline phones — stuck in basement
        // hasInternet = false
        // They carry packets but cannot upload!
        devices.put("phone-alice",
                new VirtualDevice("phone-alice", false));
        devices.put("phone-stranger1",
                new VirtualDevice("phone-stranger1", false));
        devices.put("phone-stranger2",
                new VirtualDevice("phone-stranger2", false));
        devices.put("phone-stranger3",
                new VirtualDevice("phone-stranger3", false));

        // 1 bridge phone — has internet!
        // hasInternet = true
        // This phone walks outside, gets 4G
        // Uploads packets to backend!
        devices.put("phone-bridge",
                new VirtualDevice("phone-bridge", true));
    }

    // Returns all 5 virtual phones
    // Used by ApiController for dashboard display
    // Shows state of entire mesh!
    public Collection<VirtualDevice> getDevices() {
        return devices.values();
    }

    // Returns one specific phone by its ID
    // Used by DemoService to inject packet
    // into Alice's phone specifically!
    public VirtualDevice getDevice(String id) {
        return devices.get(id);
    }

    // ═══════════════════════════════════════════════
    // METHOD: inject()
    // WHO CALLS: DemoService when Alice sends payment
    // WHAT: Puts packet into Alice's phone
    //       Starting point of the mesh journey!
    // ═══════════════════════════════════════════════
    public void inject(String senderDeviceId,
                       MeshPacket packet) {

        // Find Alice's phone in our devices map
        VirtualDevice sender = devices.get(senderDeviceId);

        // If phone doesn't exist → error!
        if (sender == null) {
            throw new IllegalArgumentException(
                    "Unknown device: " + senderDeviceId);
        }

        // Give packet to Alice's phone!
        // Journey begins here!
        sender.hold(packet);

        // Log to console
        // Only show first 8 chars of packetId
        // Full UUID too long for logs!
        log.info("Packet {} injected at {} (TTL={})",
                packet.getPacketId().substring(0, 8),
                senderDeviceId,
                packet.getTtl());
    }

    // ═══════════════════════════════════════════════
    // METHOD: gossipOnce()
    // WHO CALLS: ApiController when dashboard
    //            clicks "Run Gossip Round"
    // WHAT: Spreads ALL packets to ALL phones
    //       ONE round of Bluetooth spreading!
    //
    // Real Bluetooth: phones physically near each other
    //                 connect pair by pair naturally
    // Our simulation: everyone talks to everyone
    //                 in one round (faster demo!)
    // ═══════════════════════════════════════════════
    public GossipResult gossipOnce() {

        // Count how many packets were transferred
        // Used for logging and dashboard display
        int transfers = 0;

        // Get all phones as a list
        List<VirtualDevice> deviceList =
                new ArrayList<>(devices.values());

        // SNAPSHOT — very important!
        // Take a picture of what each phone holds
        // RIGHT NOW before gossip starts!
        //
        // Why snapshot?
        // Without snapshot:
        // Alice shares with Stranger1 in this round
        // Then Stranger1 immediately shares with Stranger2
        // Then Stranger2 shares with Stranger3
        // All in ONE gossip round!
        // That's cheating — too fast!
        //
        // With snapshot:
        // Only share what phones had at START of round
        // New packets received this round
        // only shared in NEXT round!
        // Realistic simulation!
        Map<String, List<MeshPacket>> snapshot =
                new HashMap<>();
        for (VirtualDevice d : deviceList) {
            snapshot.put(
                    d.getDeviceId(),
                    new ArrayList<>(d.getHeldPackets()));
        }

        // For each source phone (src)
        for (VirtualDevice src : deviceList) {

            // For each packet this phone had
            // at START of round (from snapshot)
            for (MeshPacket pkt :
                    snapshot.get(src.getDeviceId())) {

                // TTL check!
                // If TTL is 0 → packet is dead!
                // Cannot forward anymore!
                // Skip this packet!
                if (pkt.getTtl() <= 0) continue;

                // For each destination phone (dst)
                for (VirtualDevice dst : deviceList) {

                    // Don't send to yourself!
                    if (dst == src) continue;

                    // Don't send if dst already has it!
                    // No point sending duplicates!
                    if (dst.holds(pkt.getPacketId())) continue;

                    // Create a COPY of packet for dst
                    // Why copy not original?
                    // Each phone needs its own object!
                    // Modifying TTL on one shouldn't
                    // affect others!
                    MeshPacket copy = new MeshPacket();
                    copy.setPacketId(pkt.getPacketId());

                    // DECREMENT TTL by 1!
                    // This is the hop count!
                    // TTL 5 → 4 → 3 → 2 → 1 → 0 (dead!)
                    copy.setTtl(pkt.getTtl() - 1);

                    // Same creation time
                    copy.setCreatedAt(pkt.getCreatedAt());

                    // Same ciphertext!
                    // Encrypted payment doesn't change!
                    // Strangers cannot modify it!
                    copy.setCiphertext(pkt.getCiphertext());

                    // Give copy to destination phone!
                    dst.hold(copy);

                    // Count this transfer
                    transfers++;
                }
            }
        }

        // Log how many transfers happened
        log.info("Gossip round complete: {} packet transfers",
                transfers);

        // Return result with:
        // transfers = how many packets moved
        // snapshotMap = how many packets each phone has
        return new GossipResult(transfers, snapshotMap());
    }

    // ═══════════════════════════════════════════════
    // METHOD: snapshotMap()
    // WHAT: How many packets each phone currently holds
    // Used by dashboard to show mesh state
    //
    // Returns:
    // {
    //   "phone-alice": 1,
    //   "phone-stranger1": 1,
    //   "phone-bridge": 1
    // }
    // ═══════════════════════════════════════════════
    public Map<String, Integer> snapshotMap() {

        // LinkedHashMap keeps insertion order!
        // So dashboard shows phones in same order
        // every time! Not random order!
        Map<String, Integer> m = new LinkedHashMap<>();
        for (VirtualDevice d : devices.values()) {
            m.put(d.getDeviceId(), d.packetCount());
        }
        return m;
    }

    // ═══════════════════════════════════════════════
    // METHOD: collectBridgeUploads()
    // WHO CALLS: ApiController when "Flush Bridges"
    //            button clicked on dashboard
    // WHAT: Gets all packets from bridge phones
    //       These are ready to upload to backend!
    //
    // In real life: bridge phone walks outside
    //               gets 4G automatically
    //               uploads everything it holds
    // In demo: we click button to simulate this!
    // ═══════════════════════════════════════════════
    public List<BridgeUpload> collectBridgeUploads() {

        List<BridgeUpload> out = new ArrayList<>();

        for (VirtualDevice d : devices.values()) {

            // Only bridge phones with internet!
            // Skip offline phones!
            if (!d.hasInternet()) continue;

            // For each packet bridge phone holds
            for (MeshPacket pkt : d.getHeldPackets()) {

                // Create upload record with:
                // → which bridge phone is uploading
                // → the actual packet
                out.add(new BridgeUpload(
                        d.getDeviceId(), pkt));
            }
        }

        // Return all packets ready to upload!
        // ApiController will call
        // BridgeIngestionService.ingest() on each!
        return out;
    }

    // ═══════════════════════════════════════════════
    // METHOD: resetMesh()
    // WHO CALLS: Dashboard reset button
    // WHAT: Clear ALL packets from ALL phones
    //       Fresh start for new demo!
    // ═══════════════════════════════════════════════
    public void resetMesh() {
        // VirtualDevice::clear = method reference
        // Same as: d -> d.clear()
        // Calls clear() on each VirtualDevice!
        devices.values().forEach(VirtualDevice::clear);
    }

    // ═══════════════════════════════════════════════
    // INNER RECORDS — Simple data containers
    // Java 'record' = automatic constructor,
    // getters, equals, toString!
    // ═══════════════════════════════════════════════

    // Result of one gossip round
    // transfers = how many packets moved
    // deviceCounts = how many packets each phone has
    public record GossipResult(
            int transfers,
            Map<String, Integer> deviceCounts) {}

    // One packet ready for upload from bridge phone
    // bridgeNodeId = which bridge phone
    // packet = the actual MeshPacket to upload
    public record BridgeUpload(
            String bridgeNodeId,
            MeshPacket packet) {}
}