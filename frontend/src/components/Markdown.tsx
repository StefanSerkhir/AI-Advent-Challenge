import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import remarkMath from "remark-math";
import rehypeKatex from "rehype-katex";
import "katex/dist/katex.min.css";

// Convert the desktop's LaTeX fences only outside fenced/inline code. Raw HTML stays disabled.
function mathFences(text: string) {
  return text
    .split(/(```[\s\S]*?```|~~~[\s\S]*?~~~|`[^`\n]*`)/g)
    .map((part, index) =>
      index % 2
        ? part
        : part
            .replace(
              /\\\[([\s\S]*?)\\\]/g,
              (_, formula: string) => `\n$$\n${formula}\n$$\n`,
            )
            .replace(/\\\((.*?)\\\)/g, (_, formula: string) => `$${formula}$`),
    )
    .join("");
}
// Preserve the desktop's line breaks inside table cells without parsing arbitrary HTML.
type MarkdownNode = { type: string; value?: string; children?: MarkdownNode[] };
function remarkLineBreaks() {
  return (tree: MarkdownNode) => {
    const visit = (node: MarkdownNode) => {
      if (node.type === "html" && /^<br\s*\/?>$/i.test(node.value ?? "")) {
        node.type = "break";
        delete node.value;
      }
      node.children?.forEach(visit);
    };
    visit(tree);
  };
}

export function Markdown({ children }: { children: string }) {
  return (
    <div className="markdown">
      <ReactMarkdown
        remarkPlugins={[remarkGfm, remarkMath, remarkLineBreaks]}
        rehypePlugins={[[rehypeKatex, { trust: false, strict: "ignore" }]]}
        skipHtml
        components={{
          table: ({ children }) => (
            <div
              className="table-scroll"
              tabIndex={0}
              role="region"
              aria-label="Таблица ответа"
            >
              <table>{children}</table>
            </div>
          ),
          a: ({ href, children }) => (
            <a href={href} target="_blank" rel="noreferrer noopener">
              {children}
            </a>
          ),
          img: ({ alt }) => (
            <span className="muted">
              [Изображение: {alt || "без описания"}]
            </span>
          ),
          pre: ({ children }) => <pre tabIndex={0}>{children}</pre>,
        }}
      >
        {mathFences(children)}
      </ReactMarkdown>
    </div>
  );
}
