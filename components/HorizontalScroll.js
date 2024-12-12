export default function HorizontalScroll({ children }) {
    return (
        <div className="overflow-x-auto flex gap-4 ">
        {children}
      </div>
    );
  }
  