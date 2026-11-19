"""对照课程单表工作簿，输出排序后私密字段不可见的行数和摘要。"""

import hashlib
import json
import re
import sys
import xml.etree.ElementTree as xml
import zipfile
from pathlib import Path

SHEET = {"m": "http://schemas.openxmlformats.org/spreadsheetml/2006/main"}
ADDRESS = re.compile(r"([A-Z]+)([0-9]+)")


def unpack(workbook, width, header, expand=False):
    """读取验证所需的源列，并展开功能清单前两列中的纵向合并。"""
    with zipfile.ZipFile(workbook) as source:
        sheets = [name for name in source.namelist() if re.fullmatch(r"xl/worksheets/sheet\d+\.xml", name)]
        if len(sheets) != 1:
            raise ValueError(f"预期单表工作簿，实际工作表数为 {len(sheets)}")
        sheet = xml.fromstring(source.read(sheets[0]))
        strings = []
        if "xl/sharedStrings.xml" in source.namelist():
            root = xml.fromstring(source.read("xl/sharedStrings.xml"))
            strings = [
                "".join(part.text or "" for part in node.findall(".//m:t", SHEET))
                for node in root.findall("m:si", SHEET)
            ]
        cell_values = {}
        row_ids = []
        for row in sheet.findall(".//m:sheetData/m:row", SHEET):
            number = int(row.attrib["r"])
            if number <= header:
                continue
            row_ids.append(number)
            for cell in row.findall("m:c", SHEET):
                coordinate = cell.attrib["r"]
                if cell.get("t") == "inlineStr":
                    value = "".join(node.text or "" for node in cell.findall(".//m:t", SHEET))
                else:
                    value = cell.findtext("m:v", default="", namespaces=SHEET)
                    if cell.get("t") == "s":
                        value = strings[int(value)]
                cell_values[coordinate] = value if value.strip() else None
        if expand:
            for merged in sheet.findall(".//m:mergeCell", SHEET):
                first, last = merged.attrib["ref"].split(":")
                start_col, start_row = ADDRESS.fullmatch(first).groups()
                end_col, end_row = ADDRESS.fullmatch(last).groups()
                if start_col == end_col and start_col in ("A", "B") and int(start_row) > header:
                    for row in range(int(start_row) + 1, int(end_row) + 1):
                        cell_values[f"{start_col}{row}"] = cell_values.get(first)
        columns = "ABCDEFGHI"[:width]
        rows = [[cell_values.get(f"{column}{i}") for column in columns] for i in row_ids]
        return [row for row in rows if any(value is not None for value in row)]


def mask(phone):
    if phone is None:
        return None
    if len(phone) < 9:
        return "****"
    return phone[:3] + "****" + phone[-4:]


def fingerprint(rows):
    encoded = [json.dumps(row, ensure_ascii=False, separators=(",", ":")).encode("utf-8") for row in rows]
    return {"rows": len(rows), "digest": hashlib.sha256(b"\n".join(sorted(encoded))).hexdigest()}


def main():
    folder = Path(sys.argv[1])
    employees = unpack(folder / "市政公司员工信息表.xlsx", 6, 1)
    for row in employees:
        row[5] = mask(row[5])
    organizations = unpack(folder / "市政公司部门信息表.xlsx", 3, 1)
    catalog = unpack(folder / "市政公司应用软件功能权限清单(1).xlsx", 9, 2, expand=True)
    print(json.dumps({
        "employees": fingerprint(employees),
        "departments": fingerprint(organizations),
        "catalog-entries": fingerprint(catalog),
    }))


if __name__ == "__main__":
    main()
