export default function CategoryCard({ icon, label }) {
    return (
      <div className="flex flex-col items-center bg-white shadow-lg p-4 rounded-lg">
        <img src={icon} alt={label} className="w-12 h-12 mb-2" />
        <span className="text-sm font-medium">{label}</span>
      </div>
    );
  }
  