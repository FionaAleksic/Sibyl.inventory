# Legacy parity source map

These are exact **read-only source snapshots** from the original Niko Web-System public repository, supplied to aid real frontend and API parity work. They are **not compiled or loaded by the new Sibyl Core** and must not be mistaken for production-ready SDK code.

Test baseline commit: `0431c74405fdd79f8f4620ebdb2b535b7913eb83`  
Production source commit: `c97708e0172a3e67b0df522fbb98087935d0a3d6`

- `reference/test/Addons/inventory/module.json` — original `Addons/inventory/module.json`
- `reference/test/Addons/inventory/backend-src/niko/inventory/InventoryModule.java` — original `Addons/inventory/backend-src/niko/inventory/InventoryModule.java`
- `reference/test/Addons/inventory/frontend-src/main.tsx` — original `Addons/inventory/frontend-src/main.tsx`
- `reference/test/frontend-next/src/modules/inventory/pages/InventoryPage.tsx` — original `frontend-next/src/modules/inventory/pages/InventoryPage.tsx`
- `reference/test/frontend-next/src/modules/inventory/services/inventoryService.ts` — original `frontend-next/src/modules/inventory/services/inventoryService.ts`
- `reference/test/frontend-next/src/modules/inventory/pages/InventoryDeviceDetails.tsx` — original `frontend-next/src/modules/inventory/pages/InventoryDeviceDetails.tsx`

## Porting requirements
- Replace imports of `app.webplatform.*` with a stable, versioned Sibyl Core add-on SDK.
- Keep the original UI design/behavior unchanged for end users; compare against the old test and production pages.
- Isolate DB migrations and do not assume that tables from Niko's monolith exist in Sibyl Core.
- Route AD reads through `Sibyl.ad` rather than keeping per-add-on LDAP credentials.
- Test against disposable Cluster-3 test infrastructure first; do not change any production hosts.
