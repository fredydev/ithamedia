export default function HorizontalScroll({ children }) {
    return (
      <div className="overflow-x-auto">
        <div className="flex gap-4">{children}</div>
      </div>
    );
  }
  