

import HorizontalScroll from '../components/HorizontalScroll';
import RecommendationCard from '../components/RecommendationCard';
import CategoryCard from '../components/CategoryCard';
// import { recommendations } from '../data/recommendations';
import { categories } from '../data/categories';
import { latest } from '../data/latest';


// Fonction pour récupérer les chaînes depuis l'API
async function fetchChannels() {
  const res = await fetch('http://localhost:5000/api/channels', { cache: 'no-store' });

  if (!res.ok) {
    throw new Error('Erreur lors de la récupération des chaînes');
  }

  return res.json();
}

export default async function HomePage() {
  const channels = await fetchChannels();
  return (
    <div className="container mx-auto px-4">
      {/* Section : Chaînes recommandées */}
      <section className="my-8">
        <h2 className="text-2xl font-bold mb-4">Chaînes recommandées</h2>
        <HorizontalScroll>
          {channels.map((item) => (
            <RecommendationCard
              key={item._id}
              name={item.name}
              platform={item.platform}
              description={item.description}
              verified={item.verified}
              logoUrl={item.logoUrl}
            />
          ))}
        </HorizontalScroll>
      </section>

      {/* Section : Catégories */}
      <section className="my-8">
        <h2 className="text-2xl font-bold mb-4">Catégories</h2>
        <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 gap-4">
          {categories.map((category) => (
            <CategoryCard key={category.id} icon={category.icon} label={category.label} />
          ))}
        </div>
      </section>

      {/* Section : Derniers ajouts */}
      <section className="my-8">
        <h2 className="text-2xl font-bold mb-4">Derniers ajouts</h2>
        <HorizontalScroll>
          {latest.map((item) => (
            <RecommendationCard
              key={item._id}
              name={item.name}
              platform={item.platform}
              description={item.description}
              
            />
          ))}
        </HorizontalScroll>
      </section>
    </div>
  );
}

