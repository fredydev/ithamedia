export const recommendations = [
    {
      _id: 1,
      name: "Science Étonnante",
      platform: "YouTube",
      description: "Vulgarisation scientifique passionnante",
      verified: true,
    },
    {
      _id: 2,
      name: "Histoire en Courtes",
      platform: "TikTok",
      description: "Histoire mondiale en vidéos courtes",
      verified: true,
    },
    {
      _id: 3,
      name: "Math Explainer",
      platform: "YouTube",
      description: "Concepts mathématiques simplifiés",
      verified: true,
    },
    {
        _id: 12,
        name: "Horticulture Étonnante",
        platform: "YouTube",
        description: "Vulgarisation Horticulture passionnante",
        verified: true,
      },
      {
        _id: 23,
        name: "Pemlo en Courtes",
        platform: "TikTok",
        description: "Histoire Pemlo en vidéos courtes",
        verified: true,
      },
      {
        _id: 34,
        name: "Java Explainer",
        platform: "YouTube",
        description: "Concepts Java simplifiés",
        verified: true,
      },
  ];


import { fetchRecommendations } from './api';
import RecommendationCard from '../components/RecommendationCard';

export async function getServerSideProps() {
  try {
    const recommendations = await fetchRecommendations();
    return {
      props: {
        recommendations,
      },
    };
  } catch (error) {
    return {
      props: {
        recommendations: [],
      },
    };
  }
}

export default function Recommendations({ recommendations }) {
  return (
    <div className="container mx-auto p-4">
      <h1 className="text-2xl font-bold mb-4">Chaînes recommandées</h1>
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
        {recommendations.map((channel) => (
          <RecommendationCard
            key={channel._id}
            title={channel.name}
            platform={channel.platform}
            description={channel.description}
            verified={channel.isVerified}
          />
        ))}
      </div>
    </div>
  );
}
