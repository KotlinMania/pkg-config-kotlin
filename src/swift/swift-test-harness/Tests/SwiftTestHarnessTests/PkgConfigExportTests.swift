import PkgConfig
import Testing

@Suite("PkgConfig Swift Export Tests")
struct PkgConfigExportTests {
    @Test("Config builder and VersionBound sealed hierarchy behave properly")
    func configBuilderAndVersionBounds() throws {
        let config = Config.Companion.shared.new()
            .atleastVersion(vers: "1.0")
            .rangeVersion(
                start: VersionBound.Included(value: "1.0"),
                end: VersionBound.Excluded(value: "2.0")
            )
            .statik(statik: true)
            .cargoMetadata(cargoMetadata: false)
            .envMetadata(envMetadata: false)
            .arg(arg: "--silence-errors")

        #expect(config.targetSupported())

        switch VersionBound.Included(value: "1.2").sealedType() {
        case let .included(wrapper):
            #expect(wrapper.value.value == "1.2")
        case .excluded, .unbounded:
            Issue.record("Expected VersionBound.Included")
        }

        switch VersionBound.Unbounded.shared.sealedType() {
        case .unbounded:
            break
        case .included, .excluded:
            Issue.record("Expected VersionBound.Unbounded")
        }
    }

    @Test("Defines stores, queries, and compares compiler defines")
    func definesStorageAndEquality() throws {
        let defines = Defines()
        defines.put(name: "DEBUG", value: "1")
        defines.put(name: "FEATURE", value: nil)

        #expect(defines.size == 2)
        #expect(defines.contains(name: "DEBUG"))
        #expect(defines["DEBUG"] == "1")
        #expect(defines["FEATURE"] == nil)
        #expect(defines.toList().count == 2)
        #expect(Define(name: "DEBUG", value: "1") == Define(name: "DEBUG", value: "1"))
    }

    @Test("Error variants format actionable diagnostic messages")
    func errorFormattingAndIoError() throws {
        let envErr = Error.EnvNoPkgConfig(name: "FOO_NO_PKG_CONFIG")
        #expect(envErr.message == "Aborted because FOO_NO_PKG_CONFIG is set")

        #expect(Error.CrossCompilation.shared.message.contains("PKG_CONFIG_SYSROOT_DIR"))

        let cmdErr = Error.Command(
            command: "pkg-config --libs foo",
            ioCause: IoError(kind: .NotFound, message: "missing")
        )
        #expect(cmdErr.message.contains("pkg-config"))
    }
}
