package org.sibyl.addons.inventory;
import java.time.*;
import java.util.*;
public final class InventorySnapshotTest {
  public static void main(String[] args) {
    var all=InventorySnapshot.addressList("192.168.10.0/29",
        Map.of("192.168.10.3","Laptop-3"), Set.of("192.168.10.5"));
    check(all.size()==6,"all usable IP addresses included");
    check(all.get(2).state().equals("leased"),"DHCP lease shown");
    check(all.get(4).state().equals("reserved"),"reservation shown");
    check(all.get(0).state().equals("free"),"free address shown");
    Instant now=Instant.parse("2026-10-12T00:00:00Z");
    check(InventorySnapshot.isOnline(now.minusSeconds(5),now,Duration.ofSeconds(30)),"fresh ICMP");
    check(!InventorySnapshot.isOnline(now.minusSeconds(90),now,Duration.ofSeconds(30)),"stale ICMP");
    check(!InventorySnapshot.isOnline(now.plusSeconds(5),now,Duration.ofSeconds(30)),"future ICMP");
    try { InventorySnapshot.addressList("192.168.10.0/29",
        Map.of("192.168.11.3","invalid"),Set.of()); throw new AssertionError("CIDR escape accepted"); }
    catch(IllegalArgumentException expected) {}
    System.out.println("InventorySnapshotTest PASS");
  }
  private static void check(boolean value,String what) { if(!value)throw new AssertionError(what); }
}
