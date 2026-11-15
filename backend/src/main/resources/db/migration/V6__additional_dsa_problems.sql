WITH seeded AS (
INSERT INTO problems (slug, title, difficulty, category, description, input_format, output_format, constraints_text, explanation, published) VALUES
('reverse-linked-list', 'Reverse a linked list', 'EASY', 'Linked Lists',
 'Build a singly linked list from the input. Reverse its links and print the values from the new head to the tail.',
 'Read n followed by n integers in list order.',
 'Print the values of the reversed list separated by spaces. Print an empty line if the list is empty.',
 '0 ≤ n ≤ 50000. Values are signed 32-bit integers. Aim to reverse the links in one pass using constant extra space.',
 'The original tail becomes the new head.', TRUE),
('binary-tree-depth', 'Find the depth of a binary tree', 'EASY', 'Trees',
 'Given the children of each node in a binary tree, find the number of nodes on the longest path from its root to a leaf.',
 'Read n, then n pairs of left and right child indices in node order. The root is node 0 when n is positive. An index of -1 means no child.',
 'Print the maximum depth. Print 0 for an empty tree.',
 '0 ≤ n ≤ 50000. Nonnegative indices are in [0, n). The input describes a single rooted tree.',
 'The path through nodes 0, 1, and 3 contains three nodes.', TRUE),
('recursive-digit-sum', 'Sum the digits recursively', 'EASY', 'Recursion',
 'Find the sum of the digits of a nonnegative decimal integer. Practice reducing the problem to the sum of the remaining digits.',
 'Read one line containing the digits of the integer. Leading zeroes are allowed.',
 'Print the sum of its digits.',
 'The input contains 1 to 200 ASCII digits. Read the input as text to support integers larger than 64 bits.',
 'For 12034, the sum is 1 + 2 + 0 + 3 + 4 = 10.', TRUE),
('grid-word-search', 'Find a word in a grid', 'MEDIUM', 'Backtracking',
 'Find whether a word can be traced in a grid by moving up, down, left, or right. A cell can be used at most once in a path.',
 'Read rows and columns, then one row of letters per line, followed by the word. All letters are uppercase ASCII.',
 'Print YES if a path spells the word, or NO otherwise.',
 '1 ≤ rows, columns ≤ 6. The word contains 1 to 12 letters.',
 'ABCCED can be traced from the top left by moving right, right, down, down, and left.', TRUE),
('range-sum-queries', 'Answer range sum queries', 'MEDIUM', 'Arrays',
 'Given a fixed array and several inclusive ranges, report the sum of each range.',
 'Read n and q, then n integers. Each of the next q lines contains zero-based indices l and r.',
 'Print one sum per line, in query order.',
 '1 ≤ n, q ≤ 100000. 0 ≤ l ≤ r < n. Values are signed 32-bit integers. Use a signed 64-bit sum. Aim for O(n + q) time.',
 'The sum at indices 1 through 3 is -2 + 5 + 4 = 7.', TRUE)
ON CONFLICT (slug) DO NOTHING
RETURNING id, slug
)

INSERT INTO problem_test_cases (problem_id, position, input_text, expected_output, visible)
SELECT p.id, t.position, t.input_text, t.expected_output, t.visible
FROM (VALUES
    ('reverse-linked-list', 1, E'4\n7 3 3 -2\n', '-2 3 3 7', TRUE),
    ('reverse-linked-list', 2, E'1\n9\n', '9', TRUE),
    ('reverse-linked-list', 3, E'0\n', '', FALSE),
    ('reverse-linked-list', 4, E'3\n-2147483648 0 2147483647\n', '2147483647 0 -2147483648', FALSE),
    ('binary-tree-depth', 1, E'4\n1 2\n3 -1\n-1 -1\n-1 -1\n', '3', TRUE),
    ('binary-tree-depth', 2, E'1\n-1 -1\n', '1', TRUE),
    ('binary-tree-depth', 3, E'0\n', '0', FALSE),
    ('binary-tree-depth', 4, E'5\n-1 4\n-1 -1\n3 -1\n-1 1\n2 -1\n', '5', FALSE),
    ('recursive-digit-sum', 1, E'12034\n', '10', TRUE),
    ('recursive-digit-sum', 2, E'0\n', '0', TRUE),
    ('recursive-digit-sum', 3, E'00009\n', '9', FALSE),
    ('recursive-digit-sum', 4, repeat('9', 200) || E'\n', '1800', FALSE),
    ('grid-word-search', 1, E'3 4\nABCE\nSFCS\nADEE\nABCCED\n', 'YES', TRUE),
    ('grid-word-search', 2, E'2 2\nAB\nCD\nABA\n', 'NO', TRUE),
    ('grid-word-search', 3, E'2 3\nACX\nABC\nABCAXCX\n', 'NO', FALSE),
    ('grid-word-search', 4, E'2 3\nAAA\nAAB\nAAABA\n', 'YES', FALSE),
    ('grid-word-search', 5, E'1 1\nZ\nZ\n', 'YES', FALSE),
    ('range-sum-queries', 1, E'4 3\n3 -2 5 4\n1 3\n0 0\n1 1\n', E'7\n3\n-2', TRUE),
    ('range-sum-queries', 2, E'1 1\n-7\n0 0\n', '-7', FALSE),
    ('range-sum-queries', 3, E'3 2\n2000000000 2000000000 -1\n0 2\n0 1\n', E'3999999999\n4000000000', FALSE)
) AS t(slug, position, input_text, expected_output, visible)
JOIN seeded p ON p.slug = t.slug;
