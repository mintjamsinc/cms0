/**
 * Simple YAML Parser
 *
 * A lightweight YAML parser for parsing app.yml files.
 * Supports basic YAML features:
 * - Key-value pairs
 * - Nested objects
 * - Arrays (with - prefix)
 * - String, number, boolean values
 * - Quoted strings
 *
 * Note: This is not a full YAML parser. For complex YAML files,
 * consider using a library like js-yaml.
 */

export class YamlParser {
  /**
   * Parse a YAML string into a JavaScript object.
   *
   * @param yaml - The YAML string to parse
   * @returns Parsed JavaScript object
   */
  static parse(yaml: string): Record<string, unknown> {
    const lines = yaml.split('\n');
    const result: Record<string, unknown> = {};
    const stack: Array<{ indent: number; obj: Record<string, unknown>; key?: string; isArray?: boolean }> = [
      { indent: -1, obj: result },
    ];

    for (let i = 0; i < lines.length; i++) {
      const line = lines[i];

      // Skip empty lines and comments
      if (!line.trim() || line.trim().startsWith('#')) {
        continue;
      }

      // Calculate indentation
      const indent = line.search(/\S/);
      const content = line.trim();

      // Pop stack until we find the right level
      while (stack.length > 1 && indent <= stack[stack.length - 1].indent) {
        stack.pop();
      }

      const parent = stack[stack.length - 1];

      // Handle array items
      if (content.startsWith('- ')) {
        const arrayContent = content.substring(2).trim();

        // Ensure parent has an array for this key
        if (parent.key && !Array.isArray(parent.obj[parent.key])) {
          parent.obj[parent.key] = [];
        }

        const targetArray = parent.key
          ? (parent.obj[parent.key] as unknown[])
          : null;

        if (arrayContent.includes(':')) {
          // Inline object in array: - key: value
          const [key, ...valueParts] = arrayContent.split(':');
          const value = valueParts.join(':').trim();
          const arrayItem: Record<string, unknown> = {};
          arrayItem[key.trim()] = this.parseValue(value);
          if (targetArray) {
            targetArray.push(arrayItem);
          }
          stack.push({ indent, obj: arrayItem, isArray: true });
        } else {
          // Simple value in array
          if (targetArray) {
            targetArray.push(this.parseValue(arrayContent));
          }
        }
        continue;
      }

      // Handle key-value pairs
      const colonIndex = content.indexOf(':');
      if (colonIndex === -1) {
        continue;
      }

      const key = content.substring(0, colonIndex).trim();
      const value = content.substring(colonIndex + 1).trim();

      if (value === '') {
        // Nested object or array (value on next lines)
        const nextNonEmpty = this.findNextNonEmptyLine(lines, i + 1);
        if (nextNonEmpty && nextNonEmpty.trim().startsWith('-')) {
          // It's an array
          parent.obj[key] = [];
          stack.push({ indent, obj: parent.obj, key, isArray: true });
        } else {
          // It's a nested object
          const nestedObj: Record<string, unknown> = {};
          parent.obj[key] = nestedObj;
          stack.push({ indent, obj: nestedObj });
        }
      } else {
        // Simple key-value
        parent.obj[key] = this.parseValue(value);
      }
    }

    return result;
  }

  /**
   * Parse a YAML value into the appropriate JavaScript type.
   */
  private static parseValue(value: string): unknown {
    // Remove quotes if present
    if ((value.startsWith('"') && value.endsWith('"')) ||
        (value.startsWith("'") && value.endsWith("'"))) {
      return value.slice(1, -1);
    }

    // Boolean
    if (value === 'true' || value === 'True' || value === 'TRUE') {
      return true;
    }
    if (value === 'false' || value === 'False' || value === 'FALSE') {
      return false;
    }

    // Null
    if (value === 'null' || value === 'Null' || value === 'NULL' || value === '~') {
      return null;
    }

    // Number
    if (/^-?\d+$/.test(value)) {
      return parseInt(value, 10);
    }
    if (/^-?\d+\.\d+$/.test(value)) {
      return parseFloat(value);
    }

    // String
    return value;
  }

  /**
   * Find the next non-empty line.
   */
  private static findNextNonEmptyLine(lines: string[], startIndex: number): string | null {
    for (let i = startIndex; i < lines.length; i++) {
      const line = lines[i];
      if (line.trim() && !line.trim().startsWith('#')) {
        return line;
      }
    }
    return null;
  }
}

export default YamlParser;
