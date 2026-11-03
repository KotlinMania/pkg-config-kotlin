import Testing
import PkgConfig

@Suite("PkgConfig Export Smoke Tests")
struct PkgConfigExportTests {
    @Test("Swift module loads cleanly")
    func testSwiftModuleLoads() throws {
        #expect(true)
    }
}
