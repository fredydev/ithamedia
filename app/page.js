import { recommendations } from '../data/recommendations';
import { categories } from '../data/categories';
import { latest } from '../data/latest';
import RecommendationCard from '../components/RecommendationCard';
import CategoryCard from '../components/CategoryCard';
import HorizontalScroll from '../components/HorizontalScroll';

export default function HomePage() {
  return (
    <div className="container mx-auto px-4">
      {/* Recommandations */}
      <section className="my-8">
        <h2 className="text-2xl font-bold mb-4">Chaînes recommandées</h2>
        <HorizontalScroll>
          {recommendations.map((item) => (
            <RecommendationCard
              key={item.id}
              title={item.title}
              platform={item.platform}
              description={item.description}
              verified={item.verified}
            />
          ))}
        </HorizontalScroll>
      </section>

      {/* Catégories */}
      <section className="my-8">
        <h2 className="text-2xl font-bold mb-4">Catégories</h2>
        <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
          {categories.map((category) => (
            <CategoryCard key={category.id} icon={category.icon} label={category.label} />
          ))}
        </div>
      </section>

      {/* Derniers ajouts */}
      <section className="my-8">
        <h2 className="text-2xl font-bold mb-4">Derniers ajouts</h2>
        <HorizontalScroll>
          {latest.map((item) => (
            <RecommendationCard
              key={item.id}
              title={item.title}
              platform={item.platform}
              description={item.description}
            />
          ))}
        </HorizontalScroll>
      </section>
    </div>
  );
}
