package output

import (
	"encoding/json"
	"fmt"
	"os"
	"reflect"
	"strings"

	"github.com/fatih/color"
	"github.com/olekukonko/tablewriter"
)

type Format string

const (
	FormatTable Format = "table"
	FormatJSON  Format = "json"
)

func Print(format Format, data any, tableHeaders []string, tableRows [][]string) {
	switch format {
	case FormatJSON:
		PrintJSON(data)
	default:
		PrintTable(tableHeaders, tableRows)
	}
}

func PrintJSON(data any) {
	enc := json.NewEncoder(os.Stdout)
	enc.SetIndent("", "  ")
	enc.Encode(data)
}

func PrintTable(headers []string, rows [][]string) {
	table := tablewriter.NewWriter(os.Stdout)
	table.SetHeader(headers)
	table.SetBorder(true)
	table.SetHeaderAlignment(tablewriter.ALIGN_LEFT)
	table.SetAlignment(tablewriter.ALIGN_LEFT)
	table.SetAutoWrapText(false)
	table.AppendBulk(rows)
	table.Render()
}

func PrintKeyValue(pairs [][]string) {
	table := tablewriter.NewWriter(os.Stdout)
	table.SetBorder(false)
	table.SetColumnSeparator(":")
	table.SetAutoWrapText(false)
	for _, pair := range pairs {
		if len(pair) == 2 {
			table.Append([]string{color.CyanString(pair[0]), pair[1]})
		}
	}
	table.Render()
}

func PrintSuccess(msg string) {
	color.Green(msg)
}

func PrintError(msg string) {
	color.Red("Error: " + msg)
}

func PrintPageInfo(total, pageNum, pageSize int) {
	if total == 0 {
		fmt.Println("No results")
		return
	}
	start := (pageNum-1)*pageSize + 1
	end := pageNum * pageSize
	if end > total {
		end = total
	}
	totalPages := (total + pageSize - 1) / pageSize
	if totalPages == 0 {
		totalPages = 1
	}
	fmt.Printf("Showing %d-%d of %d results (page %d/%d)\n", start, end, total, pageNum, totalPages)
}

func StatusText(status int) string {
	switch status {
	case 1:
		return color.GreenString("Active")
	case 0:
		return color.YellowString("Disabled")
	default:
		return fmt.Sprintf("Unknown(%d)", status)
	}
}

func BoolText(v bool) string {
	if v {
		return color.GreenString("Yes")
	}
	return color.RedString("No")
}

func ToRowFields(obj any, fields ...string) []string {
	v := reflect.ValueOf(obj)
	if v.Kind() == reflect.Ptr {
		v = v.Elem()
	}

	row := make([]string, 0, len(fields))
	for _, field := range fields {
		parts := strings.Split(field, ".")
		val := v
		for _, part := range parts {
			if val.Kind() == reflect.Ptr {
				val = val.Elem()
			}
			f := val.FieldByName(part)
			if !f.IsValid() {
				row = append(row, "")
				continue
			}
			row = append(row, fmt.Sprintf("%v", f.Interface()))
		}
	}
	return row
}
