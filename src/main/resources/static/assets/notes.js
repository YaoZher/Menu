import { element } from './common.js';

export function getNoteLines(data) {
  if (Array.isArray(data?.noteLines)) {
    return data.noteLines.filter(line => ['INGREDIENT', 'STEP', 'NOTE'].includes(line?.type) && typeof line.text === 'string' && line.text.trim());
  }
  return String(data?.notes || '').split(/\r\n?|\n/).flatMap(raw => {
    const line = raw.trim();
    const marker = line.match(/^([•·]|\/\/)\s*/);
    const text = (marker ? line.slice(marker[0].length) : line).trim();
    return text ? [{ type: marker ? marker[1] === '//' ? 'STEP' : 'INGREDIENT' : 'NOTE', text }] : [];
  });
}

export function renderNoteContent(data) {
  const lines = getNoteLines(data);
  if (!lines.length) return null;
  const content = element('div', 'prep-notes');
  for (const [type, label, listTag] of [['INGREDIENT', '材料', 'ul'], ['STEP', '步骤', 'ol']]) {
    const selected = lines.filter(line => line.type === type);
    if (!selected.length) continue;
    const section = element('section', `prep-section prep-${type.toLowerCase()}`);
    section.append(element('h5', '', label));
    const list = element(listTag, 'prep-list');
    selected.forEach(line => list.append(element('li', '', line.text)));
    section.append(list);
    content.append(section);
  }
  const plain = lines.filter(line => line.type === 'NOTE');
  if (plain.length) content.append(element('p', 'prep-plain', plain.map(line => line.text).join('\n')));
  return content;
}

export function insertNoteMarker(value, selectionStart, selectionEnd, marker, maxLength = 2000) {
  if (!['•', '//'].includes(marker)) return null;
  const start = Math.max(0, Math.min(value.length, selectionStart));
  const end = Math.max(start, Math.min(value.length, selectionEnd));
  const blockStart = start === 0 ? 0 : value.lastIndexOf('\n', start - 1) + 1;
  const effectiveEnd = end > start && value[end - 1] === '\n' ? end - 1 : end;
  const nextBreak = value.indexOf('\n', effectiveEnd);
  const blockEnd = nextBreak === -1 ? value.length : nextBreak;
  const oldBlock = value.slice(blockStart, blockEnd);
  const changes = oldBlock.split('\n').map(line => {
    const match = line.match(/^([\t ]*)(?:(?:[•·]|\/\/)[\t ]*)?/);
    const prefix = `${match[1]}${marker} `;
    return { old: line, oldPrefix: match[0].length, prefix, updated: prefix + line.slice(match[0].length) };
  });
  const newBlock = changes.map(line => line.updated).join('\n');
  const updatedValue = value.slice(0, blockStart) + newBlock + value.slice(blockEnd);
  if (updatedValue.length > maxLength) return null;
  function movePosition(position) {
    let oldOffset = 0;
    let newOffset = 0;
    const relative = position - blockStart;
    for (const line of changes) {
      if (relative <= oldOffset + line.old.length) {
        return blockStart + newOffset + line.prefix.length + Math.max(0, relative - oldOffset - line.oldPrefix);
      }
      oldOffset += line.old.length + 1;
      newOffset += line.updated.length + 1;
    }
    return blockStart + newBlock.length + relative - oldBlock.length;
  }
  return { value: updatedValue, selectionStart: movePosition(start), selectionEnd: movePosition(end) };
}
