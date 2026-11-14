package dev.ftcsim.importer.gradle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.importer.ImportException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ExportedProjectTest {

    @Test
    void javaResourcesAreReadFromTheFieldsAfterTheOnesAnOlderExportHad() {
        ExportedProject export = ExportedProject.parse(List.of(
                "ftcsim-export\t1",
                "gradle\t8.9",
                "variant\tdebug",
                "application\t:TeamCode\torg.team\tC:\\team\\out\tC:\\team\\more",
                "classes\tC:\\team\\classes",
                "library\tmodule\tcom.bylazar:field:1.0.6\tC:\\field-runtime.jar\tC:\\field.aar\tC:\\field\\classes.jar\tC:\\field\\libs\\x.jar",
                "library\tproject\t:FtcRobotController\tC:\\frc.jar\t-\tC:\\frc\\out",
                "library\tfile\tlegacy.jar\tC:\\legacy.jar\t-",
                "library\tmodule\tcom.acme:resources-only:1\t-\t-\tC:\\only.jar",
                "module\t:TeamCode\tapplication\torg.team"));

        assertEquals(List.of(Path.of("C:\\team\\out"), Path.of("C:\\team\\more")), export.applicationJavaResources());
        List<ExportedProject.Library> libraries = export.libraries();
        assertEquals(List.of("com.bylazar:field:1.0.6", ":FtcRobotController", "legacy.jar", "com.acme:resources-only:1"), libraries.stream().map(ExportedProject.Library::id).toList());
        assertEquals(Optional.of(Path.of("C:\\field-runtime.jar")), libraries.get(0).classes());
        assertEquals(Optional.of(Path.of("C:\\field.aar")), libraries.get(0).aar());
        assertEquals(List.of(Path.of("C:\\field\\classes.jar"), Path.of("C:\\field\\libs\\x.jar")), libraries.get(0).javaResources());
        assertEquals(List.of(Path.of("C:\\frc\\out")), libraries.get(1).javaResources());
        assertEquals(List.of(), libraries.get(2).javaResources(), "a record of the older shape has none");
        assertEquals(Optional.empty(), libraries.get(3).classes());
        assertEquals(List.of(Path.of("C:\\only.jar")), libraries.get(3).javaResources());
    }

    @Test
    void anExportOfTheOlderShapeHasNoJavaResourcesAndIsStillRead() {
        ExportedProject export = ExportedProject.parse(List.of(
                "ftcsim-export\t1",
                "application\t:TeamCode\torg.team",
                "classes\tC:\\team\\classes",
                "library\tmodule\torg.firstinspires.ftc:RobotCore:11.1.0\tC:\\rc.jar\tC:\\rc.aar",
                "module\t:TeamCode\tapplication\torg.team"));

        assertEquals(List.of(), export.applicationJavaResources());
        assertEquals(List.of(), export.libraries().get(0).javaResources());
        assertTrue(export.libraries().get(0).isFtcSdk());
    }

    @Test
    void aRecordThatIsNotKnownIsStillRefused() {
        ImportException failure = assertThrows(ImportException.class, () -> ExportedProject.parse(List.of(
                "ftcsim-export\t1", "application\t:TeamCode\torg.team", "surprise\tx")));

        assertTrue(failure.getMessage().contains("unknown export record 'surprise'"), failure.getMessage());
    }
}
