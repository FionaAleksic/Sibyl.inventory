package org.sibyl.addons.inventory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic inventory/IPAM domain core. It never scans the network or
 * reads AD itself; authorized collectors and the AD dependency supply evidence.
 */
public final class InventorySnapshot {
    public static final String MODULE_ID = "Sibyl.inventory";
    public static final String VERSION = "0.1.0-rc.1";
    public record Address(String ipv4, String state, String owner) {}
    private InventorySnapshot() {}

    public static boolean isOnline(Instant lastIcmpSuccess, Instant now, Duration maxAge) {
        if (lastIcmpSuccess == null || now == null || maxAge == null ||
            maxAge.isNegative() || maxAge.isZero()) return false;
        Duration age = Duration.between(lastIcmpSuccess, now);
        return !age.isNegative() && age.compareTo(maxAge) <= 0;
    }

    /** Enumerate ALL usable IPv4 addresses, not just DHCP leases.
     * Strict bound prevents uncontrolled expansion of a subnet.
     */
    public static List<Address> addressList(String cidr, Map<String,String> leases,
                                            Set<String> reserved) {
        if (cidr == null || leases == null || reserved == null)
            throw new IllegalArgumentException("Network and evidence required");
        String[] parts = cidr.split("/", -1);
        if (parts.length != 2) throw new IllegalArgumentException("Invalid CIDR");
        int prefix;
        try { prefix = Integer.parseInt(parts[1]); }
        catch (NumberFormatException ex) { throw new IllegalArgumentException("Invalid prefix", ex); }
        if (prefix < 16 || prefix > 30)
            throw new IllegalArgumentException("Only /16 to /30 supported");
        long address = ipv4(parts[0]);
        long mask = (0xffffffffL << (32-prefix)) & 0xffffffffL;
        if ((address & mask) != address)
            throw new IllegalArgumentException("CIDR must contain network address");
        long end = address | (~mask & 0xffffffffL);
        for (String value: leases.keySet()) checkHostInSubnet(ipv4(value), address, end);
        for (String value: reserved) checkHostInSubnet(ipv4(value), address, end);
        List<Address> result = new ArrayList<>();
        for (long current=address+1; current<end; current++) {
            String ip = format(current);
            String lease = leases.get(ip);
            result.add(new Address(ip, lease != null ? "leased" :
                reserved.contains(ip) ? "reserved" : "free", lease == null ? "" : lease));
        }
        return List.copyOf(result);
    }

    private static void checkHostInSubnet(long value,long start,long end) {
        if (value <= start || value >= end)
            throw new IllegalArgumentException("Lease/reservation outside usable subnet");
    }
    private static long ipv4(String value) {
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) throw new IllegalArgumentException("Not IPv4");
        long result=0;
        for (String text:parts) {
            if (!text.matches("0|[1-9][0-9]{0,2}"))
                throw new IllegalArgumentException("Invalid IPv4 octet");
            int part=Integer.parseInt(text);
            if (part>255) throw new IllegalArgumentException("Invalid IPv4 octet");
            result=(result<<8)|part;
        }
        return result;
    }
    private static String format(long value) {
        return (value>>>24)+"."+((value>>>16)&255)+"."+((value>>>8)&255)+"."+(value&255);
    }
}
