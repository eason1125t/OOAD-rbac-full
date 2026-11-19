package initialization;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellRangeAddress;
import org.springframework.stereotype.Component;

/** 读取课程工作簿，保留原表行号与权限清单的合并区域。 */
@Component
public class WorkbookReader {
    /** 工作簿中的一条源记录。 */
    public record SourceRow(int number, List<String> cells) {
        /** 读取一列，空单元格返回 null。 */
        public String value(int column) {
            return cells.get(column);
        }

        /** 按列号报告缺失字段，避免输出个人数据。 */
        public String required(int column) {
            String value = value(column);
            if (value == null) {
                throw new IllegalArgumentException("第 " + number + " 行第 " + (column + 1) + " 列为空");
            }
            return value;
        }
    }

    /** 验证表头并读取内容，合并展开仅用于权限清单的前两列。 */
    public List<SourceRow> read(Path file, String sheetName, int headerIndex,
                                List<String> headers, boolean expandMergedColumns) throws IOException {
        try (Workbook workbook = WorkbookFactory.create(file.toFile(), null, true)) {
            Sheet sheet = workbook.getSheet(sheetName);
            if (sheet == null) {
                throw new IllegalArgumentException("找不到工作表 " + sheetName);
            }
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            Row header = sheet.getRow(headerIndex);
            for (int column = 0; column < headers.size(); column++) {
                if (header == null || !headers.get(column).equals(formatter.formatCellValue(header.getCell(column)))) {
                    throw new IllegalArgumentException(sheetName + " 表头第 " + (column + 1) + " 列不匹配");
                }
            }

            List<SourceRow> rows = new ArrayList<>();
            List<CellRangeAddress> merged = expandMergedColumns
                    ? sheet.getMergedRegions().stream()
                        .filter(area -> area.getFirstRow() > headerIndex
                                && area.getFirstColumn() == area.getLastColumn() && area.getFirstColumn() < 2)
                        .toList()
                    : List.of();
            for (int index = headerIndex + 1; index <= sheet.getLastRowNum(); index++) {
                Row row = sheet.getRow(index);
                if (row == null) {
                    continue;
                }
                List<String> cells = new ArrayList<>();
                for (int column = 0; column < headers.size(); column++) {
                    Row source = row;
                    for (CellRangeAddress area : merged) {
                        if (area.isInRange(index, column)) {
                            source = sheet.getRow(area.getFirstRow());
                            break;
                        }
                    }
                    String value = formatter.formatCellValue(source.getCell(column));
                    cells.add(value.isBlank() ? null : value);
                }
                if (cells.stream().anyMatch(java.util.Objects::nonNull)) {
                    rows.add(new SourceRow(index + 1, cells));
                }
            }
            return rows;
        }
    }
}
