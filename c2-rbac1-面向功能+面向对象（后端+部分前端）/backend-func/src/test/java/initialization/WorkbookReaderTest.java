package initialization;

import initialization.WorkbookReader.SourceRow;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 使用合成 XLSX 验证表头、真实合并区域和空值。 */
class WorkbookReaderTest {
    private static final String SHEET_NAME = "权限清单";
    private static final List<String> HEADERS = List.of("系统", "模块", "功能", "动作");
    private final WorkbookReader reader = new WorkbookReader();

    @TempDir
    private Path temporaryDirectory;

    @Test
    @DisplayName("C1-M1-03 展开系统与模块合并列并保留原表动作和行号")
    void expandsOnlyActualMergedColumns() throws Exception {
        Path file = writeFixture();
        List<SourceRow> rows = reader.read(file, SHEET_NAME, 1, HEADERS, true);

        assertThat(rows).extracting(SourceRow::number).containsExactly(3, 4, 7);
        assertThat(rows.get(1).value(0)).isEqualTo("示例系统");
        assertThat(rows.get(1).value(1)).isEqualTo("示例模块");
        assertThat(rows.getFirst().value(3)).isEqualTo("查看/审批");
        assertThat(rows.get(1).value(3)).isNull();
        assertThat(rows.getLast().value(0)).isNull();
        assertThatThrownBy(() -> rows.getLast().required(0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("第 7 行第 1 列");
    }

    @Test
    @DisplayName("C1-M1-04 缺失工作表或表头时报告具体位置")
    void rejectsWrongWorkbookStructure() throws Exception {
        Path file = writeFixture();

        assertThatThrownBy(() -> reader.read(file, "不存在", 1, HEADERS, true))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("找不到工作表");
        assertThatThrownBy(() -> reader.read(file, SHEET_NAME, 0, HEADERS, true))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("表头第 1 列");
        assertThatThrownBy(() -> reader.read(file, SHEET_NAME, 4, HEADERS, true))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("表头第 1 列");
    }

    /** 构造权限清单结构，含两种合并、空行和空动作。 */
    private Path writeFixture() throws Exception {
        Path file = temporaryDirectory.resolve("合成权限.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook(); OutputStream output = Files.newOutputStream(file)) {
            Sheet sheet = workbook.createSheet(SHEET_NAME);
            sheet.createRow(0).createCell(0).setCellValue("标题");
            sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 3));
            Row header = sheet.createRow(1);
            for (int column = 0; column < HEADERS.size(); column++) {
                header.createCell(column).setCellValue(HEADERS.get(column));
            }
            Row first = sheet.createRow(2);
            first.createCell(0).setCellValue("示例系统");
            first.createCell(1).setCellValue("示例模块");
            first.createCell(2).setCellValue("申请");
            first.createCell(3).setCellValue("查看/审批");
            sheet.createRow(3).createCell(2).setCellValue("撤回");
            sheet.addMergedRegion(new CellRangeAddress(2, 3, 0, 0));
            sheet.addMergedRegion(new CellRangeAddress(2, 3, 1, 1));
            sheet.createRow(5);
            sheet.createRow(6).createCell(2).setCellValue("合并区域外");
            workbook.write(output);
        }
        return file;
    }
}
