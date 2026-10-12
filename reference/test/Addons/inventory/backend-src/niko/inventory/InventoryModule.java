package niko.inventory;

import app.webplatform.platform.api.*;
import app.webplatform.db.Db;
import app.webplatform.service.InventoryService;
import app.webplatform.service.InventoryIcmpScanService;
import app.webplatform.service.InventoryZyxelDhcpCollectorService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class InventoryModule implements PlatformModuleBackend {
    private static final Logger log = LoggerFactory.getLogger(InventoryModule.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private PlatformModuleContext context;
    private DataSource dataSource;
    private Db legacyDb;
    private InventoryService legacyInventory;
    private ScheduledExecutorService backgroundJobs;

    @Override
    public void start(PlatformModuleContext context) throws Exception {
        this.context = context;
        this.dataSource = context.dataSource();
        this.legacyDb = new Db(new JdbcTemplate(dataSource), mapper);
        StandardEnvironment env = new StandardEnvironment();
        this.legacyInventory = new InventoryService(legacyDb, env) {
            @Override
            public Map<String,Object> syncNetworkCollector(Map<String,Object> body) {
                return legacyDb.transaction(() -> super.syncNetworkCollector(body));
            }
        };
        ensureSchema();
        startBackgroundJobs(env);
    }

    @Override
    public void close() {
        ScheduledExecutorService jobs = backgroundJobs;
        backgroundJobs = null;
        if (jobs != null) jobs.shutdownNow();
    }

    private void startBackgroundJobs(StandardEnvironment env) {
        InventoryIcmpScanService icmp = new InventoryIcmpScanService(
                legacyInventory,
                envBool(env,"INVENTORY_ICMP_SCAN_ENABLED",true),
                envInt(env,"INVENTORY_ICMP_SCAN_THREADS",32),
                envInt(env,"INVENTORY_ICMP_SCAN_TIMEOUT_SECONDS",1),
                envText(env,"INVENTORY_ICMP_SCAN_COLLECTOR_ID",""));

        InventoryZyxelDhcpCollectorService router = new InventoryZyxelDhcpCollectorService(
                legacyInventory,
                envBoolFallback(env,"ROUTER_COLLECTOR_ENABLED","ZYXEL_COLLECTOR_ENABLED",false),
                envTextFallback(env,"ROUTER_CONNECT","ZYXEL_HOST",""),
                envIntFallback(env,"ROUTER_PORT","ZYXEL_PORT",22),
                envTextFallback(env,"ROUTER_USERNAME","ZYXEL_USERNAME",""),
                envTextFallback(env,"ROUTER_PASSWORD","ZYXEL_PASSWORD_B64",""),
                envTextFallback(env,"ROUTER_KNOWN_HOSTS","ZYXEL_KNOWN_HOSTS",""),
                envIntFallback(env,"ROUTER_CONNECT_TIMEOUT_MS","ZYXEL_CONNECT_TIMEOUT_MS",10000),
                envIntFallback(env,"ROUTER_COMMAND_TIMEOUT_MS","ZYXEL_COMMAND_TIMEOUT_MS",15000),
                envTextFallback(env,"ROUTER_ALLOWED_CIDRS","ZYXEL_ALLOWED_CIDRS","172.22.0.0/16"),
                envTextFallback(env,"ROUTER_COLLECTOR_ID","ZYXEL_COLLECTOR_ID",""));

        long icmpInitial=Math.max(0,envLong(env,"INVENTORY_ICMP_SCAN_INITIAL_DELAY_MS",45000));
        long icmpInterval=Math.max(10000,envLong(env,"INVENTORY_ICMP_SCAN_INTERVAL_MS",300000));
        long routerInitial=Math.max(0,envLongFallback(env,"ROUTER_INITIAL_DELAY_MS","ZYXEL_INITIAL_DELAY_MS",30000));
        long routerInterval=Math.max(10000,envLongFallback(env,"ROUTER_INTERVAL_MS","ZYXEL_INTERVAL_MS",300000));

        backgroundJobs=Executors.newScheduledThreadPool(2,r -> {
            Thread t=new Thread(r,"niko-inventory-background");
            t.setDaemon(true);
            return t;
        });
        backgroundJobs.scheduleWithFixedDelay(
                () -> safeJob("icmp-scan",icmp::runOnce),
                icmpInitial,icmpInterval,TimeUnit.MILLISECONDS);
        backgroundJobs.scheduleWithFixedDelay(
                () -> safeJob("router-dhcp",router::runOnce),
                routerInitial,routerInterval,TimeUnit.MILLISECONDS);
        log.info("Inventory addon background jobs started: ICMP={}ms, router={}ms",icmpInterval,routerInterval);
    }

    private static void safeJob(String name,Runnable task) {
        try { task.run(); }
        catch (Throwable ex) { log.warn("Inventory addon background job {} failed: {}",name,ex.toString()); }
    }

    @Override
    public PlatformModuleResponse handle(PlatformModuleRequest request) throws Exception {
        String method = request.method().toUpperCase(Locale.ROOT);
        String path = normalize(request.path());

        // User-facing API: delegate to the exact pre-module inventory implementation.
        // This preserves the old UI contract (online/offline, full IPAM list,
        // component/peripheral presentation and all existing field semantics).
        if (method.equals("GET") && path.equals("/summary")) return json(legacyInventory.summary());
        if (method.equals("GET") && path.equals("/items")) {
            return json(legacyInventory.listItems(
                    query(request,"q"), query(request,"status"), query(request,"type"),
                    intQuery(request,"limit",250,1,1000)));
        }
        if (method.equals("POST") && path.equals("/items")) {
            requireAdmin(request);
            Map<String,Object> body=parseBody(request);
            return json(legacyDb.transaction(() -> legacyInventory.createManual(body,request.username())));
        }
        if (method.equals("GET") && path.matches("/items/[^/]+")) {
            return json(legacyInventory.getItem(segment(path,2)));
        }
        if (method.equals("PUT") && path.matches("/items/[^/]+")) {
            requireAdmin(request);
            String id=segment(path,2); Map<String,Object> body=parseBody(request);
            return json(legacyDb.transaction(() -> legacyInventory.updateManual(id,body,request.username())));
        }
        if (method.equals("GET") && path.matches("/items/[^/]+/history")) {
            return json(legacyInventory.history(segment(path,2),intQuery(request,"limit",100,1,500)));
        }

        if (method.equals("GET") && path.equals("/components/summary")) return json(legacyInventory.componentSummary());
        if (method.equals("GET") && path.equals("/components")) {
            return json(legacyInventory.listComponents(
                    query(request,"q"), query(request,"status"), query(request,"type"),
                    intQuery(request,"limit",500,1,2000)));
        }
        if (method.equals("PUT") && path.matches("/components/[^/]+/status")) {
            requireAdmin(request);
            String id=segment(path,2); String status=value(parseBody(request),"status");
            return json(legacyDb.transaction(() -> legacyInventory.updateComponentStatus(id,status,request.username())));
        }
        if (method.equals("GET") && path.equals("/peripherals")) {
            return json(legacyInventory.listPeripherals(query(request,"q"),intQuery(request,"limit",500,1,1000)));
        }

        if (method.equals("GET") && path.equals("/ip/subnets")) return json(legacyInventory.listIpSubnets());
        if (method.equals("GET") && path.matches("/ip/subnets/[^/]+/addresses")) {
            String status = query(request,"status");
            return json(legacyInventory.listIpAddresses(
                    segment(path,3),
                    query(request,"q"),
                    status.isBlank() ? "all" : status));
        }
        if (method.equals("PUT") && path.matches("/ip/subnets/[^/]+")) {
            requireAdmin(request);
            String id=segment(path,3); Map<String,Object> body=parseBody(request);
            return json(legacyDb.transaction(() -> legacyInventory.updateIpSubnet(id,body,request.username())));
        }

        if (method.equals("GET") && path.equals("/locations")) return json(legacyInventory.locations());
        if (method.equals("POST") && path.equals("/locations")) {
            requireAdmin(request);
            Map<String,Object> body=parseBody(request);
            return json(legacyDb.transaction(() -> legacyInventory.createLocation(body,request.username())));
        }
        if (method.equals("PUT") && path.matches("/locations/[^/]+")) {
            requireAdmin(request);
            String id=segment(path,2); Map<String,Object> body=parseBody(request);
            return json(legacyDb.transaction(() -> legacyInventory.updateLocation(id,body,request.username())));
        }

        if (method.equals("GET") && path.equals("/number-schemes")) return json(legacyInventory.numberSchemes());
        if (method.equals("PUT") && path.matches("/number-schemes/[^/]+")) {
            requireAdmin(request);
            String type=segment(path,2); Map<String,Object> body=parseBody(request);
            return json(legacyDb.transaction(() -> legacyInventory.updateNumberScheme(type,body,request.username())));
        }

        // Compatibility endpoints used by the already deployed Windows agents
        // and network collectors. They stay on /api/inventory/... through the
        // platform externalApi mount and keep the existing token contract.
        if (method.equals("POST") && path.equals("/agent/identify")) {
            legacyInventory.requireAgentToken(request.header("x-inventory-token"));
            Map<String,Object> body=parseBody(request);
            String source=firstNonBlank(request.header("x-inventory-source"),"pxe");
            return json(legacyDb.transaction(() -> legacyInventory.identify(body,source,false)));
        }
        if (method.equals("POST") && path.equals("/agent/report")) {
            legacyInventory.requireAgentToken(request.header("x-inventory-token"));
            Map<String,Object> body=parseBody(request);
            String source=firstNonBlank(request.header("x-inventory-source"),"windows-agent");
            return json(legacyDb.transaction(() -> legacyInventory.identify(body,source,true)));
        }
        if (method.equals("POST") && path.equals("/ip/collector/report")) {
            legacyInventory.requireAgentToken(request.header("x-inventory-token"));
            Map<String,Object> body=parseBody(request);
            return json(legacyDb.transaction(() -> legacyInventory.syncNetworkCollector(body)));
        }

        if (method.equals("GET") && path.equals("/ad/status")) return dependencyCall(request, "/status", "GET", new byte[0]);
        if (method.equals("POST") && path.equals("/ad/sync")) {
            requireAdmin(request);
            return json(syncFromAd(request));
        }
        return response(404, Map.of("detail", "Inventar-Endpunkt wurde nicht gefunden."));
    }

    private Map<String,Object> summary() throws SQLException {
        if (isMySql()) {
            Map<String,Object> row = one("""
                    SELECT COUNT(*) AS total,
                      COALESCE(SUM(CASE WHEN i.status='ACTIVE' THEN 1 ELSE 0 END),0) AS active,
                      COALESCE(SUM(CASE WHEN i.status='STORAGE' THEN 1 ELSE 0 END),0) AS storage,
                      COALESCE(SUM(CASE WHEN i.status='REPAIR' THEN 1 ELSE 0 END),0) AS repair,
                      COALESCE(SUM(CASE WHEN i.status IN ('RETIRED','DISPOSED') THEN 1 ELSE 0 END),0) AS retired,
                      COALESCE(SUM(CASE WHEN c.item_id IS NOT NULL AND TRIM(c.agent_version)<>'' AND
                        c.last_seen>=DATE_SUB(UTC_TIMESTAMP(6), INTERVAL
                          GREATEST(30, COALESCE(c.agent_interval_seconds,300)*3) SECOND)
                        THEN 1 ELSE 0 END),0) AS online,
                      COALESCE(SUM(CASE WHEN c.item_id IS NOT NULL AND TRIM(c.agent_version)<>'' AND
                        c.last_seen<DATE_SUB(UTC_TIMESTAMP(6), INTERVAL
                          GREATEST(30, COALESCE(c.agent_interval_seconds,300)*3) SECOND)
                        THEN 1 ELSE 0 END),0) AS offline,
                      COALESCE(SUM(CASE WHEN c.item_id IS NOT NULL AND
                        c.last_seen>=DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 36 HOUR)
                        THEN 1 ELSE 0 END),0) AS recently_seen
                    FROM inventory_items i
                    LEFT JOIN inventory_computers c ON c.item_id=i.id
                    """);
            return row == null ? Map.of() : row;
        }
        Map<String,Object> row = one("""
                SELECT COUNT(*) AS total,
                  COUNT(*) FILTER (WHERE i.status='ACTIVE') AS active,
                  COUNT(*) FILTER (WHERE i.status='STORAGE') AS storage,
                  COUNT(*) FILTER (WHERE i.status='REPAIR') AS repair,
                  COUNT(*) FILTER (WHERE i.status IN ('RETIRED','DISPOSED')) AS retired,
                  COUNT(*) FILTER (
                    WHERE c.item_id IS NOT NULL AND BTRIM(c.agent_version)<>''
                      AND c.last_seen >= now() - (GREATEST(30,COALESCE(c.agent_interval_seconds,300)*3) * interval '1 second')
                  ) AS online,
                  COUNT(*) FILTER (
                    WHERE c.item_id IS NOT NULL AND BTRIM(c.agent_version)<>''
                      AND c.last_seen < now() - (GREATEST(30,COALESCE(c.agent_interval_seconds,300)*3) * interval '1 second')
                  ) AS offline,
                  COUNT(*) FILTER (
                    WHERE c.item_id IS NOT NULL AND c.last_seen >= now() - interval '36 hours'
                  ) AS recently_seen
                FROM inventory_items i
                LEFT JOIN inventory_computers c ON c.item_id=i.id
                """);
        return row == null ? Map.of() : row;
    }

    private List<Map<String,Object>> listItems(PlatformModuleRequest request) throws SQLException {
        String q = query(request,"q").trim().toLowerCase(Locale.ROOT);
        String status = query(request,"status").trim();
        String type = query(request,"type").trim();
        int limit = intQuery(request,"limit",250,1,1000);

        StringBuilder sql = new StringBuilder(isMySql() ? """
                SELECT i.id,i.inventory_number,i.item_type,i.name,i.status,
                       i.manufacturer,i.model,i.serial_number,i.assigned_to,
                       i.updated_at,l.name AS location_name,
                       c.hostname,c.smbios_uuid,c.last_seen,c.agent_interval_seconds,
                       CASE
                         WHEN c.item_id IS NULL OR TRIM(c.agent_version)='' THEN 'UNKNOWN'
                         WHEN c.last_seen>=DATE_SUB(UTC_TIMESTAMP(6), INTERVAL
                           GREATEST(30,COALESCE(c.agent_interval_seconds,300)*3) SECOND)
                           THEN 'ONLINE'
                         ELSE 'OFFLINE'
                       END AS connectivity_status,
                       c.current_account AS `current_user`,c.last_account AS `last_user`,
                       COALESCE(NULLIF(c.current_account,''),NULLIF(c.last_account,''),i.name) AS display_name,
                       (SELECT COUNT(*) FROM inventory_network_interfaces n
                        WHERE n.item_id=i.id AND n.active=true) AS network_interface_count
                FROM inventory_items i
                LEFT JOIN inventory_locations l ON l.id=i.location_id
                LEFT JOIN inventory_computers c ON c.item_id=i.id
                WHERE 1=1
                """ : """
                SELECT i.id,i.inventory_number,i.item_type,i.name,i.status,
                       i.manufacturer,i.model,i.serial_number,i.assigned_to,
                       i.updated_at,l.name AS location_name,
                       c.hostname,c.smbios_uuid,c.last_seen,c.agent_interval_seconds,
                       CASE
                         WHEN c.item_id IS NULL OR BTRIM(c.agent_version)='' THEN 'UNKNOWN'
                         WHEN c.last_seen >= now() - (
                           GREATEST(30,COALESCE(c.agent_interval_seconds,300)*3) * interval '1 second'
                         ) THEN 'ONLINE'
                         ELSE 'OFFLINE'
                       END AS connectivity_status,
                       c.current_account AS current_user,c.last_account AS last_user,
                       COALESCE(NULLIF(c.current_account,''),NULLIF(c.last_account,''),i.name) AS display_name,
                       (SELECT COUNT(*) FROM inventory_network_interfaces n
                        WHERE n.item_id=i.id AND n.active=true) AS network_interface_count
                FROM inventory_items i
                LEFT JOIN inventory_locations l ON l.id=i.location_id
                LEFT JOIN inventory_computers c ON c.item_id=i.id
                WHERE 1=1
                """);
        List<Object> args = new ArrayList<>();
        if (!q.isBlank()) {
            sql.append("""
                     AND (
                       LOWER(i.inventory_number) LIKE ? OR LOWER(i.name) LIKE ?
                       OR LOWER(i.manufacturer) LIKE ? OR LOWER(i.model) LIKE ?
                       OR LOWER(i.serial_number) LIKE ? OR LOWER(COALESCE(c.hostname,'')) LIKE ?
                       OR LOWER(COALESCE(c.current_account,'')) LIKE ?
                       OR LOWER(COALESCE(c.last_account,'')) LIKE ?
                       OR EXISTS (
                         SELECT 1 FROM inventory_computer_users u
                         WHERE u.item_id=i.id AND LOWER(u.account_name) LIKE ?
                       )
                     )
                    """);
            String like="%"+q+"%";
            for(int i=0;i<9;i++) args.add(like);
        }
        if (!status.isBlank() && !status.equalsIgnoreCase("all")) {
            sql.append(" AND i.status=?");
            args.add(upper(status));
        }
        if (!type.isBlank() && !type.equalsIgnoreCase("all")) {
            sql.append(" AND i.item_type=?");
            args.add(upper(type));
        }
        sql.append(" ORDER BY LOWER(COALESCE(NULLIF(c.current_account,''),NULLIF(c.last_account,''),i.name)), i.inventory_number LIMIT ")
           .append(limit);
        return query(sql.toString(),args.toArray());
    }

    private Map<String,Object> getItem(String id) throws Exception {
        String deviceSql = isMySql() ? """
                SELECT i.*,l.name AS location_name,l.code AS location_code,
                       c.smbios_uuid,c.hostname,c.domain,c.os_name,c.os_version,c.os_build,c.bios_version,
                       c.secure_boot,c.tpm_version,c.first_seen,c.last_seen,c.agent_version,c.agent_interval_seconds,
                       CASE
                         WHEN c.item_id IS NULL OR TRIM(c.agent_version)='' THEN 'UNKNOWN'
                         WHEN c.last_seen>=DATE_SUB(UTC_TIMESTAMP(6), INTERVAL
                           GREATEST(30,COALESCE(c.agent_interval_seconds,300)*3) SECOND) THEN 'ONLINE'
                         ELSE 'OFFLINE'
                       END AS connectivity_status,
                       c.current_account AS `current_user`,c.last_account AS `last_user`,
                       COALESCE(NULLIF(c.current_account,''),NULLIF(c.last_account,''),i.name) AS display_name
                FROM inventory_items i
                LEFT JOIN inventory_locations l ON l.id=i.location_id
                LEFT JOIN inventory_computers c ON c.item_id=i.id
                WHERE i.id=?
                """ : """
                SELECT i.*,l.name AS location_name,l.code AS location_code,
                       c.smbios_uuid,c.hostname,c.domain,c.os_name,c.os_version,c.os_build,c.bios_version,
                       c.secure_boot,c.tpm_version,c.first_seen,c.last_seen,c.agent_version,c.agent_interval_seconds,
                       CASE
                         WHEN c.item_id IS NULL OR BTRIM(c.agent_version)='' THEN 'UNKNOWN'
                         WHEN c.last_seen >= now() - (
                           GREATEST(30,COALESCE(c.agent_interval_seconds,300)*3) * interval '1 second'
                         ) THEN 'ONLINE'
                         ELSE 'OFFLINE'
                       END AS connectivity_status,
                       c.current_account AS current_user,c.last_account AS last_user,
                       COALESCE(NULLIF(c.current_account,''),NULLIF(c.last_account,''),i.name) AS display_name
                FROM inventory_items i
                LEFT JOIN inventory_locations l ON l.id=i.location_id
                LEFT JOIN inventory_computers c ON c.item_id=i.id
                WHERE i.id=?
                """;
        Map<String,Object> row=one(deviceSql,idParam(id));
        if(row==null) throw new NoSuchElementException("Inventargerät wurde nicht gefunden.");

        String nicSql = isMySql()
                ? "SELECT id,interface_name,mac_address,last_ip,first_seen,last_seen,active FROM inventory_network_interfaces WHERE item_id=? ORDER BY active DESC,interface_name,mac_address"
                : "SELECT id,interface_name,mac_address,host(last_ip) AS last_ip,first_seen,last_seen,active FROM inventory_network_interfaces WHERE item_id=? ORDER BY active DESC,interface_name,mac_address";
        row.put("network_interfaces",query(nicSql,idParam(id)));

        List<Map<String,Object>> deviceComponents=query("""
                SELECT id,component_key,component_type,manufacturer,model,serial_number,
                       capacity_bytes,attributes,first_seen,last_seen,active,manual_status
                FROM inventory_hardware_components
                WHERE item_id=?
                ORDER BY active DESC,component_type,component_key
                """,idParam(id));
        deviceComponents.removeIf(this::isStorageSpaceComponent);
        for(Map<String,Object> c:deviceComponents)c.put("component_status",componentStatus(c));
        row.put("components",deviceComponents);

        List<Map<String,Object>> attached=query("""
                SELECT p.*,c.id AS connection_id,c.connection_type,c.connection_key,c.connection_path,
                       c.first_seen AS connection_first_seen,c.last_seen AS connection_last_seen,
                       c.disconnected_at,c.active AS connection_active
                FROM inventory_peripheral_connections c
                JOIN inventory_peripherals p ON p.id=c.peripheral_id
                WHERE c.computer_item_id=? AND c.active=true
                ORDER BY p.device_type,LOWER(COALESCE(NULLIF(p.manufacturer,''),p.model)),LOWER(p.model),p.device_uid
                """,idParam(id));
        row.put("peripherals",attached);

        row.put("peripheral_connection_history",query("""
                SELECT c.id AS connection_id,c.first_seen AS connected_at,c.last_seen,
                       c.disconnected_at,c.active,c.connection_type,c.connection_key,c.connection_path,
                       p.id AS peripheral_id,p.device_uid,p.device_type,p.identity_method,p.identity_confidence,
                       p.manufacturer,p.model,p.serial_number,p.vendor_id,p.product_id,p.identity_scope
                FROM inventory_peripheral_connections c
                JOIN inventory_peripherals p ON p.id=c.peripheral_id
                WHERE c.computer_item_id=?
                ORDER BY c.active DESC,c.last_seen DESC,c.id DESC
                LIMIT 100
                """,idParam(id)));

        try {
            row.put("known_users",query("""
                    SELECT account_name,first_seen,last_seen,last_login_at,currently_logged_on,source
                    FROM inventory_computer_users
                    WHERE item_id=?
                    ORDER BY currently_logged_on DESC,(last_login_at IS NULL),last_login_at DESC,last_seen DESC,LOWER(account_name)
                    """,idParam(id)));
        } catch (SQLException ignored) {
            row.put("known_users",List.of());
        }
        return row;
    }

    private Map<String,Object> createItem(Map<String,Object> body, String actor) throws Exception {
        String id = UUID.randomUUID().toString();
        String inventoryNumber = value(body,"inventory_number");
        if (inventoryNumber.isBlank()) inventoryNumber = "INV-" + System.currentTimeMillis();
        String type = upper(valueOr(body,"item_type","OTHER"));
        String name = valueOr(body,"name",inventoryNumber);
        try (Connection c=dataSource.getConnection(); PreparedStatement ps=c.prepareStatement("""
                INSERT INTO inventory_items(
                    id,inventory_number,item_type,name,status,manufacturer,model,serial_number,description,
                    location_id,assigned_to,purchase_date,purchase_price,warranty_until,notes,source,
                    created_by,created_at,updated_by,updated_at
                ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """)) {
            int i=1; ps.setObject(i++,idParam(id)); ps.setString(i++,inventoryNumber); ps.setString(i++,type); ps.setString(i++,name);
            ps.setString(i++,upper(valueOr(body,"status","ACTIVE"))); ps.setString(i++,value(body,"manufacturer")); ps.setString(i++,value(body,"model"));
            ps.setString(i++,value(body,"serial_number")); ps.setString(i++,value(body,"description")); setNullableId(ps,i++,value(body,"location_id"));
            ps.setString(i++,value(body,"assigned_to")); setDate(ps,i++,value(body,"purchase_date")); setDecimal(ps,i++,value(body,"purchase_price"));
            setDate(ps,i++,value(body,"warranty_until")); ps.setString(i++,value(body,"notes")); ps.setString(i++,"manual");
            ps.setString(i++,actor); ps.setTimestamp(i++,now()); ps.setString(i++,actor); ps.setTimestamp(i++,now()); ps.executeUpdate();
        }
        history(id,actor,"manual","created","",inventoryNumber);
        return getItem(id);
    }

    private Map<String,Object> updateItem(String id, Map<String,Object> body, String actor) throws Exception {
        Map<String,Object> current = getItem(id);
        List<String> fields = List.of("inventory_number","item_type","name","status","manufacturer","model","serial_number","description","assigned_to","notes");
        StringBuilder sql=new StringBuilder("UPDATE inventory_items SET ");
        List<Object> args=new ArrayList<>();
        boolean first=true;
        for(String field:fields){
            if(!body.containsKey(field))continue;
            if(!first)sql.append(",");first=false;
            sql.append(field).append("=?");
            String v=value(body,field); if(field.equals("item_type")||field.equals("status"))v=upper(v); args.add(v);
        }
        if(body.containsKey("location_id")){if(!first)sql.append(",");first=false;sql.append("location_id=?");args.add(idParamOrNull(value(body,"location_id")));}
        if(body.containsKey("purchase_date")){if(!first)sql.append(",");first=false;sql.append("purchase_date=?");args.add(dateOrNull(value(body,"purchase_date")));}
        if(body.containsKey("purchase_price")){if(!first)sql.append(",");first=false;sql.append("purchase_price=?");args.add(decimalOrNull(value(body,"purchase_price")));}
        if(body.containsKey("warranty_until")){if(!first)sql.append(",");first=false;sql.append("warranty_until=?");args.add(dateOrNull(value(body,"warranty_until")));}
        if(first)return current;
        sql.append(",updated_by=?,updated_at=? WHERE id=?");args.add(actor);args.add(now());args.add(idParam(id));
        update(sql.toString(),args.toArray());
        history(id,actor,"manual","updated","",mapper.writeValueAsString(body));
        return getItem(id);
    }

    private List<Map<String,Object>> history(String id,int limit)throws SQLException{
        return query("SELECT * FROM inventory_item_history WHERE item_id=? ORDER BY occurred_at DESC LIMIT "+limit,idParam(id));
    }

    private Map<String,Object> componentSummary() throws SQLException {
        long total=0,installed=0,archived=0,check=0,defective=0,disposed=0;
        for(Map<String,Object> row:query("SELECT component_type,model,attributes,active,manual_status FROM inventory_hardware_components")){
            if(isStorageSpaceComponent(row))continue;
            total++;
            switch(componentStatus(row)){
                case "INSTALLED" -> installed++;
                case "ARCHIVED" -> archived++;
                case "CHECK" -> check++;
                case "DEFECTIVE" -> defective++;
                case "DISPOSED" -> disposed++;
            }
        }
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("total",total);out.put("installed",installed);out.put("archived",archived);
        out.put("check_required",check);out.put("defective",defective);out.put("disposed",disposed);
        return out;
    }

    private List<Map<String,Object>> components(PlatformModuleRequest request)throws SQLException{
        String q=query(request,"q").trim().toLowerCase(Locale.ROOT);
        String wantedStatus=upper(query(request,"status"));
        String wantedType=upper(query(request,"type"));
        int limit=intQuery(request,"limit",500,1,2000);
        List<Map<String,Object>> rows=query("""
                SELECT h.*,i.name AS computer_name,i.inventory_number AS computer_inventory_number,
                       c.hostname AS computer_hostname,h.item_id AS computer_item_id
                FROM inventory_hardware_components h
                JOIN inventory_items i ON i.id=h.item_id
                LEFT JOIN inventory_computers c ON c.item_id=i.id
                ORDER BY h.last_seen DESC
                """);
        List<Map<String,Object>> out=new ArrayList<>();
        for(Map<String,Object> row:rows){
            if(isStorageSpaceComponent(row))continue;
            String componentStatus=componentStatus(row);
            row.put("component_status",componentStatus);
            if(!wantedStatus.isBlank()&&!wantedStatus.equals("ALL")&&!wantedStatus.equals(componentStatus))continue;
            if(!wantedType.isBlank()&&!wantedType.equals("ALL")&&!wantedType.equals(upper(value(row,"component_type"))))continue;
            if(!q.isBlank()){
                String hay=String.join(" ",
                        value(row,"component_key"),value(row,"manufacturer"),value(row,"model"),value(row,"serial_number"),
                        value(row,"computer_inventory_number"),value(row,"computer_hostname"),value(row,"computer_name"))
                        .toLowerCase(Locale.ROOT);
                if(!hay.contains(q))continue;
            }
            out.add(row);
        }
        out.sort(Comparator
                .comparingInt((Map<String,Object> row)->componentStatusRank(value(row,"component_status")))
                .thenComparing(row->value(row,"component_type").toLowerCase(Locale.ROOT))
                .thenComparing(row->firstNonBlank(value(row,"manufacturer"),value(row,"model")).toLowerCase(Locale.ROOT))
                .thenComparing(row->value(row,"model").toLowerCase(Locale.ROOT))
                .thenComparing((Map<String,Object> row)->value(row,"last_seen"),Comparator.reverseOrder()));
        return out.size()>limit?new ArrayList<>(out.subList(0,limit)):out;
    }

    private List<Map<String,Object>> componentsFor(String itemId)throws SQLException{
        List<Map<String,Object>> rows=query("""
                SELECT *,item_id AS computer_item_id
                FROM inventory_hardware_components
                WHERE item_id=?
                ORDER BY component_type,model
                """,idParam(itemId));
        rows.removeIf(this::isStorageSpaceComponent);
        for(Map<String,Object> row:rows)row.put("component_status",componentStatus(row));
        return rows;
    }

    private Map<String,Object> updateComponentStatus(String id,Map<String,Object> body,String actor)throws SQLException{
        String status=upper(valueOr(body,"status","AUTO"));
        String manual=status.equals("AUTO")?null:status;
        update("UPDATE inventory_hardware_components SET manual_status=?,manual_status_updated_at=?,manual_status_updated_by=? WHERE id=?",
                manual,now(),actor,idParam(id));
        Map<String,Object> row=one("""
                SELECT h.*,i.name AS computer_name,i.inventory_number AS computer_inventory_number,
                       c.hostname AS computer_hostname,h.item_id AS computer_item_id
                FROM inventory_hardware_components h
                JOIN inventory_items i ON i.id=h.item_id
                LEFT JOIN inventory_computers c ON c.item_id=i.id
                WHERE h.id=?
                """,idParam(id));
        if(row==null)throw new NoSuchElementException("Komponente nicht gefunden.");
        row.put("component_status",componentStatus(row));
        history(value(row,"computer_item_id"),actor,"manual","component_status","",status);
        return row;
    }

    private List<Map<String,Object>> peripherals(PlatformModuleRequest request)throws SQLException{
        String q=query(request,"q").trim().toLowerCase(Locale.ROOT);
        int limit=intQuery(request,"limit",500,1,1000);
        List<Map<String,Object>> rows=query("""
                SELECT p.*,
                  (SELECT COUNT(*) FROM inventory_peripheral_connections pc
                   WHERE pc.peripheral_id=p.id AND pc.active=true) AS active_connections
                FROM inventory_peripherals p
                ORDER BY p.last_seen DESC,p.id
                """);
        if(q.isBlank())return rows.size()>limit?new ArrayList<>(rows.subList(0,limit)):rows;
        List<Map<String,Object>> out=new ArrayList<>();
        for(Map<String,Object> row:rows){
            String hay=String.join(" ",value(row,"device_uid"),value(row,"device_type"),value(row,"manufacturer"),
                    value(row,"model"),value(row,"serial_number"),value(row,"pnp_device_id")).toLowerCase(Locale.ROOT);
            if(hay.contains(q)){out.add(row);if(out.size()>=limit)break;}
        }
        return out;
    }

    private List<Map<String,Object>> peripheralsFor(String itemId)throws SQLException{
        return query("""
                SELECT p.*, pc.connection_type, pc.connection_key, pc.first_seen AS connection_first_seen,
                       pc.last_seen AS connection_last_seen, pc.disconnected_at, pc.active AS connection_active
                FROM inventory_peripheral_connections pc JOIN inventory_peripherals p ON p.id=pc.peripheral_id
                WHERE pc.computer_item_id=? ORDER BY pc.last_seen DESC
                """,idParam(itemId));
    }

    private List<Map<String,Object>> subnets()throws SQLException{
        List<Map<String,Object>> rows=query("SELECT * FROM inventory_network_subnets WHERE active=true ORDER BY cidr");
        for(Map<String,Object> row:rows){
            Object id=row.get("id");
            String cidr=value(row,"cidr");
            int slash=cidr.indexOf('/');
            if(slash>0){
                int prefix=integer(cidr.substring(slash+1),32);
                row.put("total_addresses",prefix>=0&&prefix<=32 ? (1L<<(32-prefix)) : 0L);
            }else row.put("total_addresses",0L);
            row.put("occupied",scalarLong("SELECT COUNT(DISTINCT ip_address) FROM inventory_ip_observations WHERE subnet_id=? AND active=true",id));
            row.put("reserved",scalarLong("SELECT COUNT(*) FROM inventory_ip_reservations WHERE subnet_id=?",id));
            Map<String,Set<String>> identities=new LinkedHashMap<>();
            for(Map<String,Object> obs:query("""
                    SELECT ip_address,item_id,mac_address
                    FROM inventory_ip_observations
                    WHERE subnet_id=? AND active=true
                    """,id)){
                String identity=!value(obs,"mac_address").isBlank()
                        ?"MAC:"+upper(value(obs,"mac_address"))
                        :!value(obs,"item_id").isBlank()?"ITEM:"+value(obs,"item_id"):"";
                if(!identity.isBlank())identities.computeIfAbsent(value(obs,"ip_address"),x->new LinkedHashSet<>()).add(identity);
            }
            row.put("conflicts",identities.values().stream().filter(set->set.size()>1).count());
        }
        return rows;
    }

    private List<Map<String,Object>> addresses(String subnetId,PlatformModuleRequest request)throws SQLException{
        Map<String,Object> subnet=one("SELECT * FROM inventory_network_subnets WHERE id=? AND active=true",idParam(subnetId));
        if(subnet==null)throw new NoSuchElementException("Netzwerk nicht gefunden.");
        String cidr=value(subnet,"cidr");
        int slash=cidr.indexOf('/');
        if(slash<0)throw new IllegalStateException("Ungültiges Netzwerk in der Datenbank.");
        String networkIp=cidr.substring(0,slash);
        int prefix=integer(cidr.substring(slash+1),24);
        if(prefix<20||prefix>30)throw new IllegalArgumentException("Für die IP-Listenansicht werden IPv4-Netze von /20 bis /30 unterstützt.");

        long network=ipv4ToLong(networkIp);
        long total=1L<<(32-prefix);
        String gateway=value(subnet,"gateway");

        Map<String,List<Map<String,Object>>> observations=new LinkedHashMap<>();
        String obsSql=isMySql()?"""
                SELECT o.ip_address,o.mac_address,o.source,o.source_key,o.observed_hostname,
                       o.last_seen,o.lease_expires_at,o.metadata,n.interface_name,
                       i.id AS item_id,i.inventory_number,i.name AS item_name,c.hostname
                FROM inventory_ip_observations o
                LEFT JOIN inventory_items i ON i.id=o.item_id
                LEFT JOIN inventory_computers c ON c.item_id=i.id
                LEFT JOIN inventory_network_interfaces n ON n.id=o.interface_id
                WHERE o.subnet_id=? AND o.active=true
                ORDER BY o.ip_address,o.last_seen DESC
                """:"""
                SELECT host(o.ip_address) AS ip_address,o.mac_address,o.source,o.source_key,o.observed_hostname,
                       o.last_seen,o.lease_expires_at,o.metadata,n.interface_name,
                       i.id AS item_id,i.inventory_number,i.name AS item_name,c.hostname
                FROM inventory_ip_observations o
                LEFT JOIN inventory_items i ON i.id=o.item_id
                LEFT JOIN inventory_computers c ON c.item_id=i.id
                LEFT JOIN inventory_network_interfaces n ON n.id=o.interface_id
                WHERE o.subnet_id=? AND o.active=true
                ORDER BY o.ip_address,o.last_seen DESC
                """;
        for(Map<String,Object> row:query(obsSql,idParam(subnetId))){
            observations.computeIfAbsent(value(row,"ip_address"),x->new ArrayList<>()).add(row);
        }

        Map<String,Map<String,Object>> reservations=new LinkedHashMap<>();
        String resSql=isMySql()?"""
                SELECT ip_address,label,description,reservation_type,source,source_key,mac_address,last_seen
                FROM inventory_ip_reservations WHERE subnet_id=?
                """:"""
                SELECT host(ip_address) AS ip_address,label,description,reservation_type,source,source_key,mac_address,last_seen
                FROM inventory_ip_reservations WHERE subnet_id=?
                """;
        for(Map<String,Object> row:query(resSql,idParam(subnetId)))reservations.put(value(row,"ip_address"),row);

        String q=query(request,"q").trim().toLowerCase(Locale.ROOT);
        String wanted=upper(query(request,"status"));
        if(wanted.isBlank())wanted="ALL";
        List<Map<String,Object>> out=new ArrayList<>();
        for(long offset=0;offset<total;offset++){
            String ip=longToIpv4(network+offset);
            List<Map<String,Object>> seen=observations.getOrDefault(ip,List.of());
            Map<String,Object> reservation=reservations.get(ip);
            LinkedHashSet<String> hostnames=new LinkedHashSet<>(),devices=new LinkedHashSet<>(),macs=new LinkedHashSet<>(),sources=new LinkedHashSet<>(),identities=new LinkedHashSet<>();
            Object latest=null;
            for(Map<String,Object> row:seen){
                String hostname=firstNonBlank(value(row,"observed_hostname"),firstNonBlank(value(row,"hostname"),value(row,"item_name")));
                if(!hostname.isBlank())hostnames.add(hostname);
                String device=firstNonBlank(value(row,"inventory_number"),hostname);
                if(!device.isBlank())devices.add(device);
                String mac=value(row,"mac_address");
                if(!mac.isBlank()){macs.add(mac);identities.add("MAC:"+upper(mac));}
                else if(!value(row,"item_id").isBlank())identities.add("ITEM:"+value(row,"item_id"));
                String source=value(row,"source");if(!source.isBlank())sources.add(source);
                if(latest==null)latest=row.get("last_seen");
            }
            String label=reservation==null?"":value(reservation,"label");
            String description=reservation==null?"":value(reservation,"description");
            String reservationSource=reservation==null?"":value(reservation,"source");
            String reservationMac=reservation==null?"":value(reservation,"mac_address");
            if(!reservationMac.isBlank())macs.add(reservationMac);
            if(reservation!=null&&!reservationSource.isBlank()){
                sources.add((reservationSource.equalsIgnoreCase("dhcp")||reservationSource.equalsIgnoreCase("zyxel-dhcp"))
                        ?reservationSource+"-reservierung":reservationSource);
            }
            if(devices.isEmpty()&&!label.isBlank())devices.add(label);
            boolean conflict=identities.size()>1;
            String rowStatus;
            if(offset==0)rowStatus="NETWORK";
            else if(offset==total-1)rowStatus="BROADCAST";
            else if(!gateway.isBlank()&&gateway.equals(ip))rowStatus="GATEWAY";
            else if(conflict)rowStatus="CONFLICT";
            else if(reservation!=null)rowStatus="RESERVED";
            else if(!seen.isEmpty())rowStatus="OCCUPIED";
            else rowStatus="FREE";

            Map<String,Object> row=new LinkedHashMap<>();
            row.put("ip_address",ip);row.put("status",rowStatus);
            row.put("hostname",String.join(", ",hostnames));row.put("device",String.join(", ",devices));
            row.put("mac_address",String.join(", ",macs));row.put("source",String.join(", ",sources));
            row.put("last_seen",latest);row.put("label",label);row.put("description",description);
            row.put("reservation_type",reservation==null?"":value(reservation,"reservation_type"));
            row.put("reserved",reservation!=null);row.put("observation_count",seen.size());

            String searchable=String.join(" ",ip,rowStatus,value(row,"hostname"),value(row,"device"),
                    value(row,"mac_address"),value(row,"source"),label,description).toLowerCase(Locale.ROOT);
            if(!q.isBlank()&&!searchable.contains(q))continue;
            if(!wanted.equals("ALL")&&!wanted.equals(rowStatus))continue;
            out.add(row);
        }
        return out;
    }

    private Map<String,Object> updateSubnet(String id,Map<String,Object> body,String actor)throws SQLException{
        update("UPDATE inventory_network_subnets SET name=?,description=?,updated_by=?,updated_at=? WHERE id=?",
                value(body,"name"),value(body,"description"),actor,now(),idParam(id));
        Map<String,Object> row=one("SELECT * FROM inventory_network_subnets WHERE id=?",idParam(id));
        if(row==null)throw new NoSuchElementException("Subnetz nicht gefunden.");return row;
    }

    private List<Map<String,Object>> locations()throws SQLException{return query("SELECT * FROM inventory_locations ORDER BY name");}
    private Map<String,Object> createLocation(Map<String,Object> body,String actor)throws SQLException{
        String id=UUID.randomUUID().toString();
        update("INSERT INTO inventory_locations(id,code,name,parent_id,description,created_by,created_at,updated_by,updated_at) VALUES (?,?,?,?,?,?,?,?,?)",
                idParam(id),emptyToNull(value(body,"code")),valueOr(body,"name","Standort"),idParamOrNull(value(body,"parent_id")),value(body,"description"),actor,now(),actor,now());
        return one("SELECT * FROM inventory_locations WHERE id=?",idParam(id));
    }
    private Map<String,Object> updateLocation(String id,Map<String,Object> body,String actor)throws SQLException{
        update("UPDATE inventory_locations SET code=?,name=?,description=?,updated_by=?,updated_at=? WHERE id=?",
                emptyToNull(value(body,"code")),valueOr(body,"name","Standort"),value(body,"description"),actor,now(),idParam(id));
        return one("SELECT * FROM inventory_locations WHERE id=?",idParam(id));
    }

    private List<Map<String,Object>> numberSchemes()throws SQLException{return query("SELECT * FROM inventory_number_schemes ORDER BY item_type");}
    private Map<String,Object> updateNumberScheme(String type,Map<String,Object> body,String actor)throws SQLException{
        int updated=update("UPDATE inventory_number_schemes SET prefix=?,digits=?,hostname_equals_inventory=?,auto_assign=?,enabled=?,description=?,updated_by=?,updated_at=? WHERE item_type=?",
                value(body,"prefix"),integer(body.get("digits"),5),bool(body.get("hostname_equals_inventory")),bool(body.get("auto_assign")),!body.containsKey("enabled")||bool(body.get("enabled")),value(body,"description"),actor,now(),upper(type));
        if(updated==0)update("INSERT INTO inventory_number_schemes(item_type,prefix,digits,hostname_equals_inventory,auto_assign,enabled,next_value,description,updated_by,updated_at) VALUES (?,?,?,?,?,?,1,?,?,?)",
                upper(type),value(body,"prefix"),integer(body.get("digits"),5),bool(body.get("hostname_equals_inventory")),bool(body.get("auto_assign")),!body.containsKey("enabled")||bool(body.get("enabled")),value(body,"description"),actor,now());
        return one("SELECT * FROM inventory_number_schemes WHERE item_type=?",upper(type));
    }

    private Map<String,Object> syncFromAd(PlatformModuleRequest request)throws Exception{
        PlatformModuleResponse status=context.modules().call("Sibyl.ad",new PlatformModuleRequest("GET","/status",Map.of(),"application/json",new byte[0],request.username(),request.roles()));
        if(status.status()!=200)throw new IllegalStateException("AD-Dependency ist nicht verfügbar.");
        PlatformModuleResponse result=context.modules().call("Sibyl.ad",new PlatformModuleRequest("GET","/computers",Map.of("q",List.of("")),"application/json",new byte[0],request.username(),request.roles()));
        if(result.status()!=200)throw new IllegalStateException("AD-Computer konnten nicht geladen werden.");
        @SuppressWarnings("unchecked") List<Map<String,Object>> computers=(List<Map<String,Object>>)result.body();
        int created=0,updated=0;
        for(Map<String,Object> computer:computers){
            String dns=value(computer,"dNSHostName");String hostname=value(computer,"name");
            if(hostname.isBlank()&&!dns.isBlank())hostname=dns.split("\\.")[0];
            if(hostname.isBlank())continue;
            Map<String,Object> existing=one("SELECT i.id FROM inventory_items i JOIN inventory_computers c ON c.item_id=i.id WHERE LOWER(c.hostname)=LOWER(?)",hostname);
            if(existing!=null){
                update("UPDATE inventory_computers SET domain=?,os_name=?,os_version=?,last_seen=? WHERE item_id=?",
                        domainOf(dns),value(computer,"operatingSystem"),value(computer,"operatingSystemVersion"),now(),existing.get("id"));updated++;continue;
            }
            String id=UUID.randomUUID().toString();String inv=hostname.toUpperCase(Locale.ROOT);if(inv.length()>40)inv=inv.substring(0,40);
            try{
                update("INSERT INTO inventory_items(id,inventory_number,item_type,name,status,manufacturer,model,serial_number,description,location_id,assigned_to,notes,source,created_by,created_at,updated_by,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                        idParam(id),uniqueInventoryNumber(inv),"COMPUTER",hostname,"ACTIVE","","","","Aus Active Directory importiert",null,"","","ad-sync",request.username(),now(),request.username(),now());
                update("INSERT INTO inventory_computers(item_id,hostname,domain,os_name,os_version,first_seen,last_seen,agent_version) VALUES (?,?,?,?,?,?,?,?)",
                        idParam(id),hostname,domainOf(dns),value(computer,"operatingSystem"),value(computer,"operatingSystemVersion"),now(),now(),"ad-sync");
                history(id,request.username(),"ad-sync","created","",hostname);created++;
            }catch(SQLException ex){if(isDuplicate(ex)){updated++;}else throw ex;}
        }
        return Map.of("created",created,"updated",updated,"ad_computers",computers.size());
    }

    private PlatformModuleResponse dependencyCall(PlatformModuleRequest original,String path,String method,byte[] body){
        return context.modules().call("Sibyl.ad",new PlatformModuleRequest(method,path,original.query(),original.contentType(),body,original.username(),original.roles()));
    }

    private void ensureSchema()throws SQLException{
        boolean mysql=isMySql();
        try(Connection c=dataSource.getConnection();Statement s=c.createStatement()){
            for(String sql:schema(mysql))s.execute(sql);
        }
    }

    private List<String> schema(boolean mysql){
        String id="varchar(36)";
        String ts=mysql?"timestamp(6)":"timestamp";
        String bool="boolean";
        String text="text";
        String autoId=mysql ? "bigint PRIMARY KEY AUTO_INCREMENT" : "bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY";
        return List.of(
            "CREATE TABLE IF NOT EXISTS inventory_locations(id "+id+" PRIMARY KEY,code varchar(120) UNIQUE,name varchar(240) NOT NULL,parent_id "+id+",description "+text+" NOT NULL,created_by varchar(160) NOT NULL,created_at "+ts+" NOT NULL,updated_by varchar(160) NOT NULL,updated_at "+ts+" NOT NULL)",
            "CREATE TABLE IF NOT EXISTS inventory_items(id "+id+" PRIMARY KEY,inventory_number varchar(40) NOT NULL UNIQUE,item_type varchar(80) NOT NULL,name varchar(240) NOT NULL,status varchar(40) NOT NULL,manufacturer varchar(200) NOT NULL,model varchar(240) NOT NULL,serial_number varchar(240) NOT NULL,description "+text+" NOT NULL,location_id "+id+",assigned_to varchar(240) NOT NULL,purchase_date date,purchase_price decimal(12,2),warranty_until date,notes "+text+" NOT NULL,source varchar(80) NOT NULL,created_by varchar(160) NOT NULL,created_at "+ts+" NOT NULL,updated_by varchar(160) NOT NULL,updated_at "+ts+" NOT NULL)",
            "CREATE TABLE IF NOT EXISTS inventory_computers(item_id "+id+" PRIMARY KEY,smbios_uuid varchar(120),hostname varchar(120),domain varchar(240) NOT NULL,os_name varchar(240) NOT NULL,os_version varchar(160) NOT NULL,os_build varchar(120) NOT NULL DEFAULT '',bios_version varchar(240) NOT NULL DEFAULT '',secure_boot "+bool+",tpm_version varchar(80) NOT NULL DEFAULT '',first_seen "+ts+" NOT NULL,last_seen "+ts+" NOT NULL,agent_version varchar(80) NOT NULL)",
            "CREATE TABLE IF NOT EXISTS inventory_hardware_components(id "+id+" PRIMARY KEY,item_id "+id+" NOT NULL,component_key varchar(300) NOT NULL,component_type varchar(80) NOT NULL,manufacturer varchar(200) NOT NULL,model varchar(240) NOT NULL,serial_number varchar(240) NOT NULL,capacity_bytes bigint,attributes "+text+",first_seen "+ts+" NOT NULL,last_seen "+ts+" NOT NULL,active "+bool+" NOT NULL,manual_status varchar(40),manual_status_updated_at "+ts+",manual_status_updated_by varchar(160))",
            "CREATE TABLE IF NOT EXISTS inventory_peripherals(id "+id+" PRIMARY KEY,device_uid varchar(160) NOT NULL UNIQUE,device_type varchar(80) NOT NULL,identity_method varchar(80) NOT NULL,identity_confidence varchar(20) NOT NULL,manufacturer varchar(200) NOT NULL,model varchar(240) NOT NULL,serial_number varchar(240) NOT NULL,vendor_id varchar(16) NOT NULL,product_id varchar(16) NOT NULL,container_id varchar(160) NOT NULL,pnp_device_id varchar(500) NOT NULL,hardware_ids "+text+",attributes "+text+",first_seen "+ts+" NOT NULL,last_seen "+ts+" NOT NULL,active "+bool+" NOT NULL)",
            "CREATE TABLE IF NOT EXISTS inventory_peripheral_connections(id "+autoId+",peripheral_id "+id+" NOT NULL,computer_item_id "+id+" NOT NULL,connection_type varchar(80) NOT NULL,connection_key varchar(500) NOT NULL,connection_path "+text+" NOT NULL,first_seen "+ts+" NOT NULL,last_seen "+ts+" NOT NULL,disconnected_at "+ts+",active "+bool+" NOT NULL)",
            "CREATE TABLE IF NOT EXISTS inventory_item_history(id "+autoId+",item_id "+id+" NOT NULL,occurred_at "+ts+" NOT NULL,actor varchar(160) NOT NULL,source varchar(80) NOT NULL,field_name varchar(160) NOT NULL,old_value "+text+",new_value "+text+")",
            "CREATE TABLE IF NOT EXISTS inventory_network_subnets(id "+id+" PRIMARY KEY,cidr varchar(80) NOT NULL UNIQUE,name varchar(160) NOT NULL,description "+text+" NOT NULL,gateway varchar(64),vlan_id integer,dhcp_start varchar(64),dhcp_end varchar(64),active "+bool+" NOT NULL,auto_discovered "+bool+" NOT NULL,created_by varchar(160) NOT NULL,created_at "+ts+" NOT NULL,updated_by varchar(160) NOT NULL,updated_at "+ts+" NOT NULL)",
            "CREATE TABLE IF NOT EXISTS inventory_ip_observations(id "+autoId+",subnet_id "+id+" NOT NULL,ip_address varchar(64) NOT NULL,item_id "+id+",interface_id "+id+",mac_address varchar(40) NOT NULL,source varchar(80) NOT NULL,source_key varchar(320) NOT NULL DEFAULT '',observed_hostname varchar(240) NOT NULL DEFAULT '',lease_expires_at "+ts+",metadata "+text+",first_seen "+ts+" NOT NULL,last_seen "+ts+" NOT NULL,active "+bool+" NOT NULL)",
            "CREATE TABLE IF NOT EXISTS inventory_ip_reservations(id "+id+" PRIMARY KEY,subnet_id "+id+" NOT NULL,ip_address varchar(64) NOT NULL,label varchar(200) NOT NULL,description "+text+" NOT NULL,reservation_type varchar(40) NOT NULL,source varchar(80) NOT NULL DEFAULT 'manual',source_key varchar(320) NOT NULL DEFAULT '',mac_address varchar(40) NOT NULL DEFAULT '',last_seen "+ts+",created_by varchar(160) NOT NULL,created_at "+ts+" NOT NULL,updated_by varchar(160) NOT NULL,updated_at "+ts+" NOT NULL)",
            "CREATE TABLE IF NOT EXISTS inventory_number_schemes(item_type varchar(80) PRIMARY KEY,prefix varchar(20) NOT NULL,digits integer NOT NULL,hostname_equals_inventory "+bool+" NOT NULL,auto_assign "+bool+" NOT NULL,enabled "+bool+" NOT NULL,next_value bigint NOT NULL,description "+text+" NOT NULL,updated_by varchar(160) NOT NULL,updated_at "+ts+" NOT NULL)"
        );
    }


    private boolean isStorageSpaceComponent(Map<String,Object> row){
        if(!"DISK".equalsIgnoreCase(value(row,"component_type")))return false;
        String model=value(row,"model").trim().toLowerCase(Locale.ROOT);
        if(model.equals("microsoft storage space device")||model.equals("storage space device"))return true;
        Object attrs=row.get("attributes");
        if(attrs instanceof Map<?,?> map){
            Object bus=map.get("bus_type");
            return bus!=null&&"spaces".equalsIgnoreCase(String.valueOf(bus).trim());
        }
        return false;
    }
    private static String componentStatus(Map<String,Object> row){
        String manual=upper(value(row,"manual_status"));
        if(List.of("CHECK","DEFECTIVE","DISPOSED").contains(manual))return manual;
        return bool(row.get("active"))?"INSTALLED":"ARCHIVED";
    }
    private static int componentStatusRank(String status){
        return switch(upper(status)){
            case "CHECK" -> 0;
            case "DEFECTIVE" -> 1;
            case "INSTALLED" -> 2;
            case "ARCHIVED" -> 3;
            case "DISPOSED" -> 4;
            default -> 5;
        };
    }
    private static String firstNonBlank(String first,String second){return first==null||first.isBlank()?second:first;}
    private static long ipv4ToLong(String ip){
        String[] p=ip.split("\\.");
        if(p.length!=4)throw new IllegalArgumentException("Ungültige IPv4-Adresse.");
        long out=0;
        for(String part:p){int v=Integer.parseInt(part);if(v<0||v>255)throw new IllegalArgumentException("Ungültige IPv4-Adresse.");out=(out<<8)|v;}
        return out;
    }
    private static String longToIpv4(long value){
        return ((value>>>24)&255)+"."+((value>>>16)&255)+"."+((value>>>8)&255)+"."+(value&255);
    }

    private void history(String itemId,String actor,String source,String field,String oldValue,String newValue)throws SQLException{
        update("INSERT INTO inventory_item_history(item_id,occurred_at,actor,source,field_name,old_value,new_value) VALUES (?,?,?,?,?,?,?)",idParam(itemId),now(),actor,source,field,oldValue,newValue);
    }
    private String uniqueInventoryNumber(String preferred)throws SQLException{
        String candidate=preferred;int i=2;
        while(scalarLong("SELECT COUNT(*) FROM inventory_items WHERE LOWER(inventory_number)=LOWER(?)",candidate)>0){
            String suffix="-"+i++;candidate=preferred.substring(0,Math.min(preferred.length(),40-suffix.length()))+suffix;
        }return candidate;
    }

    private List<Map<String,Object>> query(String sql,Object...args)throws SQLException{
        try(Connection c=dataSource.getConnection();PreparedStatement ps=c.prepareStatement(adaptSql(sql))){
            bind(ps,args);try(ResultSet rs=ps.executeQuery()){List<Map<String,Object>> rows=new ArrayList<>();ResultSetMetaData md=rs.getMetaData();
                while(rs.next()){Map<String,Object> row=new LinkedHashMap<>();for(int i=1;i<=md.getColumnCount();i++){String key=md.getColumnLabel(i).toLowerCase(Locale.ROOT);Object v=rs.getObject(i);if(v instanceof java.sql.Array a)v=Arrays.asList((Object[])a.getArray());row.put(key,jsonValue(v));}rows.add(row);}return rows;}
        }
    }
    private Map<String,Object> one(String sql,Object...args)throws SQLException{List<Map<String,Object>> rows=query(sql,args);return rows.isEmpty()?null:rows.get(0);}
    private int update(String sql,Object...args)throws SQLException{try(Connection c=dataSource.getConnection();PreparedStatement ps=c.prepareStatement(adaptSql(sql))){bind(ps,args);return ps.executeUpdate();}}
    private long scalarLong(String sql,Object...args)throws SQLException{try(Connection c=dataSource.getConnection();PreparedStatement ps=c.prepareStatement(adaptSql(sql))){bind(ps,args);try(ResultSet rs=ps.executeQuery()){return rs.next()?rs.getLong(1):0;}}}
    private void bind(PreparedStatement ps,Object[]args)throws SQLException{for(int i=0;i<args.length;i++)ps.setObject(i+1,args[i]);}
    private Object jsonValue(Object value){if(!(value instanceof String s))return value;String t=s.trim();if((t.startsWith("{")&&t.endsWith("}"))||(t.startsWith("[")&&t.endsWith("]"))){try{return mapper.readValue(t,Object.class);}catch(Exception ignored){}}return value;}
    private String adaptSql(String sql){return isMySql()?sql.replace("CAST(o.ip_address AS CHAR)","CAST(o.ip_address AS CHAR)"):sql.replace("CAST(o.ip_address AS CHAR)","CAST(o.ip_address AS TEXT)");}
    private boolean isMySql(){try(Connection c=dataSource.getConnection()){return c.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT).contains("mysql");}catch(Exception ex){return false;}}
    private Object idParam(String id){if(id==null||id.isBlank())return null;if(isMySql())return id;try{return UUID.fromString(id);}catch(Exception ex){return id;}}
    private Object idParamOrNull(String id){return id==null||id.isBlank()?null:idParam(id);}
    private void setNullableId(PreparedStatement ps,int index,String id)throws SQLException{Object v=idParamOrNull(id);if(v==null)ps.setNull(index,Types.VARCHAR);else ps.setObject(index,v);}
    private void setDate(PreparedStatement ps,int index,String value)throws SQLException{java.sql.Date v=dateOrNull(value);if(v==null)ps.setNull(index,Types.DATE);else ps.setDate(index,v);}
    private void setDecimal(PreparedStatement ps,int index,String value)throws SQLException{BigDecimal v=decimalOrNull(value);if(v==null)ps.setNull(index,Types.DECIMAL);else ps.setBigDecimal(index,v);}
    private static java.sql.Date dateOrNull(String value){try{return value==null||value.isBlank()?null:java.sql.Date.valueOf(value);}catch(Exception ex){return null;}}
    private static BigDecimal decimalOrNull(String value){try{return value==null||value.isBlank()?null:new BigDecimal(value);}catch(Exception ex){return null;}}
    private static Timestamp now(){return Timestamp.from(OffsetDateTime.now(ZoneOffset.UTC).toInstant());}
    private static boolean columnMissing(SQLException ex){String s=ex.getSQLState();return "42S22".equals(s)||"42703".equals(s);}
    private static boolean isDuplicate(SQLException ex){String s=ex.getSQLState();return "23000".equals(s)||"23505".equals(s);}
    private Map<String,Object> parseBody(PlatformModuleRequest request)throws Exception{
        byte[] body=request.body();
        try{
            return mapper.readValue(body,new TypeReference<>(){});
        }catch(com.fasterxml.jackson.core.JsonParseException ex){
            if(isValidUtf8(body))throw ex;
            // Windows PowerShell 5.1 sends a String -Body as the local ANSI
            // code page when no charset is specified. Existing deployed
            // inventory agents therefore contain CP1252 bytes for umlauts.
            String legacy=new String(body,Charset.forName("windows-1252"));
            return mapper.readValue(legacy,new TypeReference<>(){});
        }
    }
    private static boolean isValidUtf8(byte[] bytes){
        try{
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
            return true;
        }catch(CharacterCodingException ex){
            return false;
        }
    }
    private static String envText(StandardEnvironment env,String key,String fallback){
        String value=env.getProperty(key);
        return value==null||value.isBlank()?fallback:value.trim();
    }
    private static String envTextFallback(StandardEnvironment env,String primary,String secondary,String fallback){
        String value=env.getProperty(primary);
        if(value==null||value.isBlank())value=env.getProperty(secondary);
        return value==null||value.isBlank()?fallback:value.trim();
    }
    private static boolean envBool(StandardEnvironment env,String key,boolean fallback){
        String value=env.getProperty(key);
        return value==null||value.isBlank()?fallback:Boolean.parseBoolean(value.trim());
    }
    private static boolean envBoolFallback(StandardEnvironment env,String primary,String secondary,boolean fallback){
        String value=env.getProperty(primary);
        if(value==null||value.isBlank())value=env.getProperty(secondary);
        return value==null||value.isBlank()?fallback:Boolean.parseBoolean(value.trim());
    }
    private static int envInt(StandardEnvironment env,String key,int fallback){
        try{return Integer.parseInt(envText(env,key,String.valueOf(fallback)));}catch(Exception ex){return fallback;}
    }
    private static int envIntFallback(StandardEnvironment env,String primary,String secondary,int fallback){
        try{return Integer.parseInt(envTextFallback(env,primary,secondary,String.valueOf(fallback)));}catch(Exception ex){return fallback;}
    }
    private static long envLong(StandardEnvironment env,String key,long fallback){
        try{return Long.parseLong(envText(env,key,String.valueOf(fallback)));}catch(Exception ex){return fallback;}
    }
    private static long envLongFallback(StandardEnvironment env,String primary,String secondary,long fallback){
        try{return Long.parseLong(envTextFallback(env,primary,secondary,String.valueOf(fallback)));}catch(Exception ex){return fallback;}
    }

    private static PlatformModuleResponse json(Object body){return PlatformModuleResponse.json(body);}
    private static PlatformModuleResponse response(int status,Object body){return new PlatformModuleResponse(status,"application/json",body);}
    private static void requireAdmin(PlatformModuleRequest request){if(request.roles()==null||!request.roles().contains("admin"))throw new SecurityException("Administratorrolle erforderlich.");}
    private static String normalize(String path){if(path==null||path.isBlank())return "/";return path.startsWith("/")?path:"/"+path;}
    private static String segment(String path,int index){String[] p=path.split("/");return p.length>index?p[index]:"";}
    private static String query(PlatformModuleRequest request,String key){List<String> v=request.query().get(key);return v==null||v.isEmpty()?"":v.get(0);}
    private static int intQuery(PlatformModuleRequest request,String key,int fallback,int min,int max){int v=integer(query(request,key),fallback);return Math.max(min,Math.min(max,v));}
    private static String value(Map<String,Object> map,String key){Object v=map.get(key);return v==null?"":String.valueOf(v).trim();}
    private static String valueOr(Map<String,Object> map,String key,String fallback){String v=value(map,key);return v.isBlank()?fallback:v;}
    private static String upper(String value){return value==null?"":value.trim().toUpperCase(Locale.ROOT);}
    private static boolean bool(Object v){return v instanceof Boolean b?b:v!=null&&Boolean.parseBoolean(String.valueOf(v));}
    private static int integer(Object v,int fallback){if(v instanceof Number n)return n.intValue();try{return Integer.parseInt(String.valueOf(v));}catch(Exception ex){return fallback;}}
    private static Object emptyToNull(String s){return s==null||s.isBlank()?null:s;}
    private static String domainOf(String dns){if(dns==null)return "";int dot=dns.indexOf('.');return dot<0?"":dns.substring(dot+1);}
}
