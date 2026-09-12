import json
from pathlib import Path
import subprocess
import tempfile
import unittest

from .check import (
    MAIN, check_android_code, check_backup, check_domain, check_locales, check_schemas, check_server_storage,
)


class InvariantTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def write(self, path, content):
        file = self.root / path
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text(content)

    def test_domain_rejects_platform_dependencies_and_imports(self):
        for item in ("PackageReference", "ProjectReference", "FrameworkReference", "Reference", "Import"):
            with self.subTest(item=item):
                self.write("src/BunDo.Domain/BunDo.Domain.csproj", f'<Project><ItemGroup><{item} Include="outside"/></ItemGroup></Project>')
                self.assertEqual("ARCH001", check_domain(self.root)[0].rule)

    def test_shared_build_files_cannot_smuggle_domain_references(self):
        self.write("Directory.Build.props", '<Project><ItemGroup><PackageReference Include="Azure.Storage.Blobs"/></ItemGroup></Project>')
        self.assertTrue(check_domain(self.root))

    def test_domain_accepts_bcl_only_project(self):
        self.write("src/BunDo.Domain/BunDo.Domain.csproj", "<Project><PropertyGroup><TargetFramework>net10.0</TargetFramework></PropertyGroup></Project>")
        self.assertEqual([], check_domain(self.root))

    def test_ui_rejects_room_alias_and_fully_qualified_names(self):
        for code in ("import androidx.room.Room as R", "fi.bundo.data.InboxDatabase.open(context)", "import fi.bundo.data.InboxDao"):
            with self.subTest(code=code):
                self.write(MAIN / "java/fi/bundo/ui/Bad.kt", code)
                self.assertEqual("ARCH002", check_android_code(self.root)[0].rule)

    def test_comments_and_strings_do_not_trigger_code_checks(self):
        self.write(MAIN / "java/fi/bundo/ui/Fine.kt", '''
// import androidx.room.Room
/* InboxDatabase.open() */
val example = "fallbackToDestructiveMigration()"
val sqlExample = """allowMainThreadQueries()"""
import fi.bundo.data.InboxRepository
''')
        self.assertEqual([], check_android_code(self.root))

    def test_data_rejects_ui_dependencies(self):
        self.write(MAIN / "java/fi/bundo/data/Bad.kt", "import androidx.compose.runtime.mutableStateOf")
        self.assertEqual("ARCH003", check_android_code(self.root)[0].rule)

    def test_cosmos_sdk_stays_inside_its_adapter(self):
        for code in (
            "using Microsoft.Azure.Cosmos;",
            "using Db = Microsoft.Azure.Cosmos.CosmosClient;",
            "var c = new Microsoft.Azure.Cosmos.CosmosClient(endpoint);",
            "using Microsoft.Azure.Documents.Client;",
        ):
            with self.subTest(code=code):
                self.write("src/BunDo.Functions/SyncFunction.cs", code)
                self.assertEqual("ARCH004", check_server_storage(self.root)[0].rule)

    def test_cosmos_adapter_and_documentation_references_are_allowed(self):
        self.write("src/BunDo.Functions/Storage/Cosmos/WorkspaceStore.cs", "using Microsoft.Azure.Cosmos;")
        self.write("src/BunDo.Functions/Program.cs", '// Register Microsoft.Azure.Cosmos inside the adapter.\nservices.AddWorkspaceStorage();')
        self.write("src/BunDo.Functions/obj/Generated.cs", "using Microsoft.Azure.Cosmos;")
        self.assertEqual([], check_server_storage(self.root))

    def test_room_escape_hatches_fail(self):
        for call in ("fallbackToDestructiveMigration()", "fallbackToDestructiveMigrationFrom(1)", "fallbackToDestructiveMigrationOnDowngrade()", "allowMainThreadQueries()"):
            with self.subTest(call=call):
                self.write(MAIN / "java/fi/bundo/data/Bad.kt", "builder." + call)
                self.assertEqual("DATA001", check_android_code(self.root)[0].rule)

    def test_backup_flag_alone_is_not_enough(self):
        self.write(MAIN / "AndroidManifest.xml", '<manifest xmlns:android="http://schemas.android.com/apk/res/android"><application android:allowBackup="false"/></manifest>')
        self.assertEqual(2, len(check_backup(self.root)))

    def test_missing_device_transfer_exclusion_fails(self):
        # Start from the actual policy, then damage a single costly-to-miss domain.
        repo = Path(__file__).resolve().parents[2]
        for path in (MAIN / "AndroidManifest.xml", MAIN / "res/xml/backup_rules.xml", MAIN / "res/xml/data_extraction_rules.xml"):
            self.write(path, (repo / path).read_text())
        self.assertEqual([], check_backup(self.root))
        path = MAIN / "res/xml/data_extraction_rules.xml"
        self.write(path, (self.root / path).read_text().replace('<exclude domain="device_database" path="." />', "", 1))
        self.assertEqual("PRIV001", check_backup(self.root)[0].rule)

    def translations(self, fi, en):
        self.write(MAIN / "res/values/strings.xml", "<resources>" + fi + "</resources>")
        self.write(MAIN / "res/values-en/strings.xml", "<resources>" + en + "</resources>")

    def test_translations_allow_reordered_arguments_and_brand_constant(self):
        self.translations(
            '<string name="brand" translatable="false">Bun Do</string><string name="count">%1$d: %2$s</string>',
            '<string name="count">%2$s: %1$d</string>',
        )
        self.assertEqual([], check_locales(self.root))

    def test_translation_argument_types_and_missing_keys_fail(self):
        self.translations('<string name="count">%1$d</string><string name="missing">Hei</string>', '<string name="count">%1$s</string>')
        self.assertEqual(2, len(check_locales(self.root)))

    def test_plurals_check_each_quantity(self):
        self.translations(
            '<plurals name="count"><item quantity="one">%d asia</item><item quantity="other">%d asiaa</item></plurals>',
            '<plurals name="count"><item quantity="one">%s thing</item><item quantity="other">%d things</item></plurals>',
        )
        self.assertEqual("I18N001", check_locales(self.root)[0].rule)

    def test_schema_version_matches_filename(self):
        self.write("src/BunDo.Android/app/schemas/Database/1.json", json.dumps({"database": {"version": 2}}))
        self.assertEqual("DATA002", check_schemas(self.root, compare_git=False)[0].rule)

    def test_committed_schemas_cannot_be_changed_or_deleted(self):
        path = "src/BunDo.Android/app/schemas/Database/1.json"
        self.write(path, json.dumps({"database": {"version": 1}}))
        for command in (["init", "-q"], ["add", "."], ["-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "-qm", "fixture"]):
            subprocess.run(["git", *command], cwd=self.root, check=True)
        self.assertEqual([], check_schemas(self.root, compare_git=True))
        self.write(path, json.dumps({"database": {"version": 1, "changed": True}}))
        self.assertEqual("DATA002", check_schemas(self.root, compare_git=True)[0].rule)
        (self.root / path).unlink()
        self.assertEqual("DATA002", check_schemas(self.root, compare_git=True)[0].rule)

    def test_ci_compares_committed_changes_against_explicit_base(self):
        path = "src/BunDo.Android/app/schemas/Database/1.json"
        self.write(path, json.dumps({"database": {"version": 1}}))
        subprocess.run(["git", "init", "-q"], cwd=self.root, check=True)
        for content in ({"version": 1}, {"version": 1, "changed": True}):
            self.write(path, json.dumps({"database": content}))
            subprocess.run(["git", "add", "."], cwd=self.root, check=True)
            subprocess.run(["git", "-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "-qm", "fixture"], cwd=self.root, check=True)
        self.assertEqual([], check_schemas(self.root, compare_git=True))
        self.assertEqual("DATA002", check_schemas(self.root, compare_git=True, git_base="HEAD~1")[0].rule)
        with tempfile.TemporaryDirectory() as snapshot:
            # An empty staged snapshot represents deletion; read history from the source repository.
            findings = check_schemas(Path(snapshot), compare_git=True, git_root=self.root, git_base="HEAD~1")
            self.assertEqual("DATA002", findings[0].rule)


if __name__ == "__main__":
    unittest.main()
