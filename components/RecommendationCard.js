export default function RecommendationCard({ title, platform, description, verified }) {
    return (
      <div className="bg-white shadow-lg rounded-lg p-4 w-64">
        <div className="flex justify-between items-center mb-2">
          <h3 className="font-bold text-lg">{title}</h3>
          {verified && <span className="text-green-500 text-sm">✔ Contenu vérifié</span>}
        </div>
        <p className="text-sm text-gray-500">{description}</p>
        <p className="text-sm text-primary">{platform}</p>
      </div>
    );
  }
  