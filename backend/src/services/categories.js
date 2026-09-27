'use strict';

/**
 * The starter categories seeded into every new group.
 *
 * One level of nesting: the entries below are headings, and `children` are the
 * sub-categories under them. A heading stays selectable, because plenty of
 * shopping is just "Grocery" and making people choose a sub-category for it
 * would only push them towards whichever child was least wrong.
 *
 * The food categories are vegetarian. Nothing in the code depends on that — a
 * flat that wants other categories adds them on the Categories screen — but
 * the defaults should suit the flat they ship for rather than need editing
 * down on day one.
 *
 * Section 8's original flat list is still here in full; Vegetables and Milk
 * have simply moved under Grocery, where they were always going to belong.
 */
const DEFAULT_CATEGORIES = [
  {
    name: 'Grocery',
    icon: 'grocery',
    children: [
      { name: 'Vegetables', icon: 'vegetables' },
      { name: 'Fruits', icon: 'fruits' },
      { name: 'Milk & Dairy', icon: 'milk' },
      { name: 'Staples', icon: 'staples' },
      { name: 'Snacks', icon: 'snacks' },
      { name: 'Spices & Masala', icon: 'spices' }
    ]
  },
  {
    name: 'Utilities',
    icon: 'utilities',
    children: [
      { name: 'Electricity', icon: 'electricity' },
      { name: 'Water', icon: 'water' },
      { name: 'Gas', icon: 'gas' },
      { name: 'Internet', icon: 'internet' }
    ]
  },
  {
    name: 'Home',
    icon: 'home',
    children: [
      { name: 'Rent', icon: 'rent' },
      { name: 'Maintenance', icon: 'maintenance' },
      { name: 'Household Items', icon: 'household' },
      { name: 'Furniture', icon: 'furniture' }
    ]
  },
  {
    name: 'Help',
    icon: 'people',
    children: [
      { name: 'Cleaning', icon: 'cleaning' },
      { name: 'Cook', icon: 'cook' },
      { name: 'Laundry', icon: 'laundry' }
    ]
  },
  {
    name: 'Other',
    icon: 'other',
    children: [
      { name: 'Subscriptions', icon: 'subscriptions' },
      { name: 'Guests & Parties', icon: 'party' },
      { name: 'Medical', icon: 'medical' }
    ]
  }
];

/** Every name in the tree, headings and children alike. */
function flatten(tree = DEFAULT_CATEGORIES) {
  const out = [];
  for (const parent of tree) {
    out.push({ name: parent.name, icon: parent.icon, parent: null });
    for (const child of parent.children || []) {
      out.push({ name: child.name, icon: child.icon, parent: parent.name });
    }
  }
  return out;
}

/**
 * Inserts the starter tree for a new group. Headings go in first so the
 * children have a parent id to point at.
 *
 * Shared by registration and the seed script, because two copies of this drift
 * and a flat created through the app would end up with different categories
 * from a flat created by the seed.
 */
async function seedCategories(conn, groupId) {
  let order = 0;
  for (const parent of DEFAULT_CATEGORIES) {
    const [result] = await conn.query(
      'INSERT INTO categories (group_id, parent_id, name, icon, sort_order) VALUES (?, NULL, ?, ?, ?)',
      [groupId, parent.name, parent.icon, order++]
    );
    for (const child of parent.children || []) {
      await conn.query(
        'INSERT INTO categories (group_id, parent_id, name, icon, sort_order) VALUES (?, ?, ?, ?, ?)',
        [groupId, result.insertId, child.name, child.icon, order++]
      );
    }
  }
  return order;
}

module.exports = { DEFAULT_CATEGORIES, flatten, seedCategories };
