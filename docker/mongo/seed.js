// Runs once when the mongo volume is first created. Sample data covering the mapping strategies:
// nested objects (flatten / JSON), arrays (child table), mixed types and an updatedAt watermark.
const shop = db.getSiblingDB("shop");

shop.customers.insertMany([
  {
    _id: ObjectId("650000000000000000000001"),
    name: "Asha Rao",
    email: "asha@example.com",
    address: { city: "Pune", zip: "411001", country: "IN" },
    tags: ["vip", "newsletter"],
    createdAt: ISODate("2026-01-05T10:00:00Z"),
    updatedAt: ISODate("2026-01-05T10:00:00Z"),
  },
  {
    _id: ObjectId("650000000000000000000002"),
    name: "Daniel Kim",
    email: "daniel@example.com",
    address: { city: "Seoul", zip: "04524", country: "KR" },
    tags: [],
    createdAt: ISODate("2026-02-11T08:30:00Z"),
    updatedAt: ISODate("2026-03-01T12:00:00Z"),
  },
]);

shop.orders.insertMany([
  {
    _id: ObjectId("660000000000000000000001"),
    customerId: ObjectId("650000000000000000000001"),
    status: "SHIPPED",
    total: NumberDecimal("149.97"),
    customer: { name: "Asha Rao", email: "asha@example.com" },
    items: [
      { sku: "BOOK-001", qty: 2, price: NumberDecimal("24.99") },
      { sku: "LAMP-010", qty: 1, price: NumberDecimal("99.99") },
    ],
    meta: { source: "web", coupon: null, utm: { campaign: "spring" } },
    updatedAt: ISODate("2026-03-02T09:15:00Z"),
  },
  {
    _id: ObjectId("660000000000000000000002"),
    customerId: ObjectId("650000000000000000000002"),
    status: "PENDING",
    total: NumberDecimal("12.50"),
    customer: { name: "Daniel Kim", email: "daniel@example.com" },
    items: [{ sku: "PEN-100", qty: 5, price: NumberDecimal("2.50") }],
    meta: { source: "app" },
    updatedAt: ISODate("2026-03-05T17:40:00Z"),
  },
]);
