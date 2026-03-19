function parseAmount(text) {
  if (!text) return null;

  const match = String(text).match(/R\$\s*([\d\.\,]+)/i);
  if (!match) return null;

  const normalized = match[1].replace(/\./g, "").replace(",", ".");
  const value = Number(normalized);
  return Number.isFinite(value) ? value : null;
}

async function parseOrdersFromPage(page) {
  return await page.evaluate(() => {
    const text = (el) => (el?.innerText || el?.textContent || "").trim();

    const rows = Array.from(
      document.querySelectorAll("table tr, [role='row'], article, li, .andes-card")
    );

    const out = [];

    for (const row of rows) {
      const rowText = text(row);
      if (!rowText) continue;

      const idMatch = rowText.match(/\b\d{6,}\b/);
      if (!idMatch) continue;

      const cells = Array.from(row.querySelectorAll("td, [role='cell'], span, div"));

      out.push({
        external_order_id: idMatch[0],
        title: text(cells[0]) || null,
        buyer_name: text(cells[1]) || null,
        status: text(cells[2]) || null,
        substatus: null,
        amount_text: rowText,
        currency: "BRL"
      });
    }

    return out;
  });
}

function normalizeParsedOrders(parsedOrders) {
  return parsedOrders.map((o) => ({
    external_order_id: String(o.external_order_id || "").trim(),
    title: o.title || null,
    buyer_name: o.buyer_name || null,
    status: o.status || null,
    substatus: o.substatus || null,
    amount: parseAmount(o.amount_text || ""),
    currency: o.currency || "BRL"
  })).filter((o) => o.external_order_id);
}

module.exports = {
  parseOrdersFromPage,
  normalizeParsedOrders
};
